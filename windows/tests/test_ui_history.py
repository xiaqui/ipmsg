import os
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM","offscreen")
from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication
from cryptography.hazmat.primitives.asymmetric import rsa
from ipmsg_windows import app as client
from ipmsg_windows.protocol import fingerprint


class UiTest(unittest.TestCase):
    def test_self_and_all_selection_survive_refresh_and_name_change(self):
        application=QApplication.instance() or QApplication([])
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self_id=fingerprint(key.public_key())
        def inspect():
            window=next(w for w in application.topLevelWidgets() if hasattr(w,"select_destination"))
            window.select_destination([self_id],"自分宛て");window.refresh()
            self.assertEqual([self_id],[i.data(Qt.UserRole) for i in window.peers.selectedItems()])
            window.toggle_trust()  # self is not a trust candidate
            window.device_name.setText("M6 UI test");window.save_name()
            self.assertIn("M6 UI test（自分）",window.peers.item(0).text())
            window.select_destination([self_id],"全端末");window.refresh()
            self.assertEqual(1,len(window.peers.selectedItems()))
            window.peers.item(0).setSelected(False)
            self.assertEqual([],window.peers.selectedItems())
            window.deleteLater()
            return 0
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(client,"os",types.SimpleNamespace(name="nt",environ=os.environ)), \
             mock.patch("ipmsg_windows.identity.default_data_dir",return_value=Path(folder)), \
             mock.patch("ipmsg_windows.identity.load_or_create",return_value=key), \
             mock.patch("ipmsg_windows.network.LanNode.start"), \
             mock.patch("ipmsg_windows.network.LanNode.stop"), \
             mock.patch("PySide6.QtNetwork.QLocalSocket.waitForConnected",return_value=False), \
             mock.patch("PySide6.QtNetwork.QLocalServer.listen",return_value=True), \
             mock.patch.object(QApplication,"exec",side_effect=inspect), \
             mock.patch("sys.argv",["ipmsg"]):
            self.assertEqual(0,client.main())
