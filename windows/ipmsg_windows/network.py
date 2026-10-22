"""Bounded UDP discovery and authenticated TCP transport."""
from __future__ import annotations

import ipaddress
import os
import socket
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

import psutil
from cryptography.hazmat.primitives.asymmetric import rsa

from . import protocol
from .file_transfer import FileTransferCore
from .store import Peer, Store


_profile_lock = threading.Lock()
_profile_cache: tuple[float, set[str]] = (0.0, set())


def _private_physical_interfaces() -> set[str]:
    """Fail closed: only Windows Private-profile, up, physical Ethernet/Wi-Fi NICs."""
    global _profile_cache
    with _profile_lock:
        if time.monotonic() - _profile_cache[0] < 5:
            return set(_profile_cache[1])
        if os.name != "nt":
            _profile_cache = (time.monotonic(), set())
            return set()
        command = ("Get-NetConnectionProfile | Where-Object {$_.NetworkCategory -eq 'Private'} | "
          "ForEach-Object {$a=Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue; "
          "if($a.Status -eq 'Up' -and $a.HardwareInterface -and "
          "($a.MediaType -eq '802.3' -or $a.MediaType -eq 'Native 802.11')) {$_.InterfaceAlias}}")
        try:
            run = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                                 capture_output=True, text=True, timeout=3, check=True,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            names = {line.strip() for line in run.stdout.splitlines() if line.strip()}
        except (OSError, subprocess.SubprocessError):
            names = set()
        _profile_cache = (time.monotonic(), names)
        return set(names)


def _lan_interfaces() -> list[tuple[str, ipaddress.IPv4Network]]:
    allowed = _private_physical_interfaces()
    result = []
    for name, entries in psutil.net_if_addrs().items():
        if name not in allowed: continue
        for item in entries:
            if item.family == socket.AF_INET and item.netmask and not item.address.startswith(("127.", "169.254.")):
                try: result.append((item.address,ipaddress.IPv4Network(f"{item.address}/{item.netmask}", strict=False)))
                except ValueError: pass
    return result


def lan_broadcasts() -> set[str]:
    return {target for _source,network in _lan_interfaces()
            for target in ("255.255.255.255",str(network.broadcast_address))}


def host_on_lan(host: str) -> bool:
    try: target = ipaddress.ip_address(host)
    except ValueError: return False
    return any(target in network and target not in (network.network_address, network.broadcast_address)
               for _source,network in _lan_interfaces())


def _expire_connection(conn: socket.socket) -> None:
    try: conn.shutdown(socket.SHUT_RDWR)
    except OSError: pass
    try: conn.close()
    except OSError: pass


class LanNode:
    def __init__(self, store: Store, private: rsa.RSAPrivateKey, name: str,
                 on_change: Callable[[], None] = lambda: None,
                 on_letter: Callable[[str, str], None] = lambda _n, _b: None,
                 on_file_offer: Callable[[str, str], None] = lambda _n, _t: None):
        self.store, self.private, self.name = store, private, name[:40]
        self.self_id = protocol.fingerprint(private.public_key())
        self.on_change, self.on_letter = on_change, on_letter
        self.files = FileTransferCore(store,on_change,on_file_offer)
        self.stop_event = threading.Event()
        self.pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="ipmsg")
        self.receivers = ThreadPoolExecutor(max_workers=2, thread_name_prefix="ipmsg-rx")
        self.receive_slots = threading.BoundedSemaphore(8)
        self.active_hosts: set[str] = set()
        self.active_lock = threading.Lock()
        self.udp: socket.socket | None = None
        self.tcp: socket.socket | None = None

    def start(self) -> None:
        self.stop_event.clear()
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp.bind(("", protocol.PORT)); udp.settimeout(1); self.udp = udp
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind(("", protocol.PORT)); tcp.listen(8); tcp.settimeout(1); self.tcp = tcp
        self.pool.submit(self._udp_loop); self.pool.submit(self._tcp_loop); self.pool.submit(self._clock_loop)

    def stop(self) -> None:
        self.stop_event.set()
        for sock in (self.udp, self.tcp):
            if sock:
                try: sock.close()
                except OSError: pass
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.receivers.shutdown(wait=False, cancel_futures=True)

    def _udp_loop(self) -> None:
        sock = self.udp
        if sock is None: return
        while not self.stop_event.is_set():
            try:
                data, source = sock.recvfrom(4097)
                if not host_on_lan(source[0]): continue
                hello = protocol.read_hello(data)
                if hello.peer_id != self.self_id:
                    self.store.discover(hello.peer_id, hello.name, hello.public_key, source[0], int(time.time()*1000),
                                        protocol.FILE_CAPABILITY in hello.capabilities)
                    self.on_change()
            except socket.timeout: pass
            except (OSError, ValueError, KeyError): pass

    def _announce(self) -> None:
        packet = protocol.make_hello(self.name, self.private)
        peers = [p for p in self.store.peers() if p.trusted]
        # Bind each send socket to its allowed Private physical NIC. This retains limited
        # broadcast compatibility without allowing Windows to route it through VPN/public NICs.
        for source,network in _lan_interfaces():
            targets = {"255.255.255.255",str(network.broadcast_address)} | {
                p.host for p in peers if ipaddress.ip_address(p.host) in network}
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1);sock.bind((source,0))
                    for host in targets: sock.sendto(packet, (host, protocol.PORT))
                except (OSError,ValueError): pass

    def _tcp_loop(self) -> None:
        server = self.tcp
        if server is None: return
        while not self.stop_event.is_set():
            try:
                conn, addr = server.accept(); host = addr[0]; conn.settimeout(3)
                approved = host_on_lan(host) and any(p.trusted and p.host == host for p in self.store.peers())
                with self.active_lock:
                    admitted = approved and host not in self.active_hosts and self.receive_slots.acquire(False)
                    if admitted: self.active_hosts.add(host)
                if not admitted: conn.close(); continue
                deadline = threading.Timer(3.0, _expire_connection, (conn,));deadline.daemon=True;deadline.start()
                self.receivers.submit(self._receive_guarded, conn, host, deadline)
            except socket.timeout: pass
            except OSError: break

    def _receive_guarded(self, conn: socket.socket, host: str, deadline: threading.Timer) -> None:
        try: self._receive(conn, host)
        finally:
            deadline.cancel()
            with self.active_lock: self.active_hosts.discard(host)
            self.receive_slots.release()

    def _receive(self, conn: socket.socket, host: str) -> None:
        with conn:
            if not host_on_lan(host): return
            try:
                frame = protocol.recv_frame(conn); sender = protocol.sender_id(frame)
                peer = next((p for p in self.store.peers() if p.peer_id == sender and p.trusted), None)
                if not peer: return
                body = protocol.open_frame(frame, self.private, protocol.load_public(peer.public_key))
                if body.get("type") == "TEXT":
                    message_id, text = body["id"], body["body"]
                    inserted = self.store.receive(message_id, sender, peer.name, text)
                    response={"type":"ACK", "id":message_id}
                    if inserted:self.on_letter(peer.name,text)
                else:
                    response=self.files.handle(peer,body)
                reply = protocol.seal(response,self.private,protocol.load_public(peer.public_key))
                conn.sendall(protocol.pack_frame(reply))
                self.on_change()
            except (OSError, EOFError, ValueError, KeyError): return

    def _send(self, letter, peer: Peer) -> None:
        try:
            if not host_on_lan(peer.host): raise OSError("peer is outside active LAN")
            with socket.create_connection((peer.host, protocol.PORT), timeout=3) as conn:
                conn.settimeout(5)
                frame = protocol.seal({"type":"TEXT", "id":letter.message_id, "body":letter.body},
                                      self.private, protocol.load_public(peer.public_key))
                if not self.store.write_if_pending(letter,lambda:conn.sendall(protocol.pack_frame(frame))):
                    return
                ack = protocol.open_frame(protocol.recv_frame(conn), self.private,
                                          protocol.load_public(peer.public_key))
                if ack.get("type") != "ACK" or ack.get("id") != letter.message_id:
                    raise ValueError("invalid ACK")
            self.store.delivered(letter.message_id, peer.peer_id)
        except (OSError, EOFError, ValueError, KeyError):
            pass
        self.on_change()

    def _exchange_file(self, peer: Peer, fields: dict) -> dict:
        if not host_on_lan(peer.host): raise OSError("peer is outside active LAN")
        current=next((p for p in self.store.peers() if p.peer_id==peer.peer_id and p.trusted),None)
        if current is None:raise ValueError("UNTRUSTED")
        with socket.create_connection((peer.host,protocol.PORT),timeout=3) as conn:
            conn.settimeout(5)
            frame=protocol.seal(fields,self.private,protocol.load_public(peer.public_key))
            conn.sendall(protocol.pack_frame(frame))
            answer=protocol.open_frame(protocol.recv_frame(conn),self.private,
                                       protocol.load_public(peer.public_key))
        if not any(p.peer_id==peer.peer_id and p.trusted for p in self.store.peers()):
            raise ValueError("UNTRUSTED")
        return answer

    def _process_file_batch(self, transfer, peer: Peer) -> None:
        # Run consecutive acknowledged chunks in the worker; the one-second clock
        # is for polling and retry backoff, not a per-chunk rate limiter.
        for _ in range(128):
            before=self.store.transfer_files(transfer.transfer_id)
            signature=tuple((f.file_id,f.offset,f.state) for f in before)
            self.files.process(transfer,peer,lambda fields:self._exchange_file(peer,fields))
            updated=self.store.transfer(transfer.transfer_id,transfer.peer_id,False)
            if updated.state!="TRANSFERRING":
                break
            after=self.store.transfer_files(transfer.transfer_id)
            progress=tuple((f.file_id,f.offset,f.state) for f in after)
            if signature==progress:
                break
            transfer=updated

    def _clock_loop(self) -> None:
        next_hello = 0.0
        while not self.stop_event.wait(1):
            if time.monotonic() >= next_hello:
                self._announce(); next_hello = time.monotonic() + 10
            now = int(time.time()*1000); peers = {p.peer_id:p for p in self.store.peers()}
            for letter in self.store.claim_pending(now,16):
                peer = peers.get(letter.peer_id)
                if peer: self.pool.submit(self._send, letter, peer)
            for transfer in self.store.due_transfers(now,2):
                peer=peers.get(transfer.peer_id)
                if peer:self.pool.submit(self._process_file_batch,transfer,peer)


