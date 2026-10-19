import socket
import sys
import tempfile
import threading
import time
import unittest
import types
from collections import namedtuple
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives.asymmetric import rsa

sys.path.insert(0, str(Path(__file__).parents[1]))
try:
    import psutil  # noqa: F401
except ModuleNotFoundError:
    sys.modules["psutil"]=types.SimpleNamespace(net_if_addrs=lambda:{})
from ipmsg_windows import network
from ipmsg_windows.network import LanNode
from ipmsg_windows.store import Store


Address=namedtuple("Address","family address netmask broadcast ptp")


class NetworkPolicyTest(unittest.TestCase):
    def test_only_allowlisted_interface_contributes_lan(self):
        addresses={
          "Wi-Fi":[Address(socket.AF_INET,"192.168.1.25","255.255.255.0",None,None)],
          "VPN":[Address(socket.AF_INET,"10.8.0.2","255.255.255.0",None,None)]}
        with mock.patch.object(network,"_private_physical_interfaces",return_value={"Wi-Fi"}), \
             mock.patch.object(network.psutil,"net_if_addrs",return_value=addresses):
            self.assertTrue(network.host_on_lan("192.168.1.12"))
            self.assertFalse(network.host_on_lan("10.8.0.3"))
            self.assertEqual({"255.255.255.255","192.168.1.255"},network.lan_broadcasts())

    def test_profile_query_failure_is_fail_closed(self):
        network._profile_cache=(0.0,set())
        with mock.patch.object(network.os,"name","nt"), \
             mock.patch.object(network.subprocess,"run",side_effect=OSError("blocked")):
            self.assertEqual(set(),network._private_physical_interfaces())

    def test_absolute_receive_deadline_releases_slot(self):
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder)/"db.sqlite")
            key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
            node=LanNode(store,key,"test")
            left,right=socket.socketpair();node.active_hosts.add("192.168.1.2")
            self.assertTrue(node.receive_slots.acquire(False))
            deadline=threading.Timer(0.05,network._expire_connection,(left,));deadline.start();start=time.monotonic()
            with mock.patch.object(network,"host_on_lan",return_value=True):
                node._receive_guarded(left,"192.168.1.2",deadline)
            self.assertLess(time.monotonic()-start,1.0)
            self.assertNotIn("192.168.1.2",node.active_hosts)
            self.assertTrue(node.receive_slots.acquire(False))
            node.receive_slots.release();right.close();store.db.close();node.receivers.shutdown(False)


if __name__ == "__main__": unittest.main()
