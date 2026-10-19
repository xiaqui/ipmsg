"""Bounded UDP discovery and authenticated TCP transport."""
from __future__ import annotations

import ipaddress
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

import psutil
from cryptography.hazmat.primitives.asymmetric import rsa

from . import protocol
from .store import Peer, Store


def lan_broadcasts() -> set[str]:
    result = {"255.255.255.255"}
    for entries in psutil.net_if_addrs().values():
        for item in entries:
            if item.family == socket.AF_INET and item.netmask and not item.address.startswith("127."):
                try: result.add(str(ipaddress.IPv4Network(f"{item.address}/{item.netmask}", strict=False).broadcast_address))
                except ValueError: pass
    return result


def host_on_lan(host: str) -> bool:
    try: target = ipaddress.ip_address(host)
    except ValueError: return False
    for entries in psutil.net_if_addrs().values():
        for item in entries:
            if item.family == socket.AF_INET and item.netmask:
                try:
                    if target in ipaddress.ip_network(f"{item.address}/{item.netmask}", strict=False): return True
                except ValueError: pass
    return False


class LanNode:
    def __init__(self, store: Store, private: rsa.RSAPrivateKey, name: str,
                 on_change: Callable[[], None] = lambda: None,
                 on_letter: Callable[[str, str], None] = lambda _n, _b: None):
        self.store, self.private, self.name = store, private, name[:40]
        self.self_id = protocol.fingerprint(private.public_key())
        self.on_change, self.on_letter = on_change, on_letter
        self.stop_event = threading.Event()
        self.pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="ipmsg")
        self.udp: socket.socket | None = None
        self.tcp: socket.socket | None = None

    def start(self) -> None:
        self.stop_event.clear()
        self.pool.submit(self._udp_loop); self.pool.submit(self._tcp_loop); self.pool.submit(self._clock_loop)

    def stop(self) -> None:
        self.stop_event.set()
        for sock in (self.udp, self.tcp):
            if sock:
                try: sock.close()
                except OSError: pass
        self.pool.shutdown(wait=False, cancel_futures=True)

    def _udp_loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("", protocol.PORT)); sock.settimeout(1); self.udp = sock
        while not self.stop_event.is_set():
            try:
                data, source = sock.recvfrom(4097)
                hello = protocol.read_hello(data)
                if hello.peer_id != self.self_id:
                    self.store.discover(hello.peer_id, hello.name, hello.public_key, source[0], int(time.time()*1000))
                    self.on_change()
            except socket.timeout: pass
            except (OSError, ValueError, KeyError): pass

    def _announce(self) -> None:
        packet = protocol.make_hello(self.name, self.private)
        targets = lan_broadcasts() | {p.host for p in self.store.peers() if p.trusted and host_on_lan(p.host)}
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            for host in targets:
                try: sock.sendto(packet, (host, protocol.PORT))
                except OSError: pass

    def _tcp_loop(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("", protocol.PORT)); server.listen(8); server.settimeout(1); self.tcp = server
        while not self.stop_event.is_set():
            try:
                conn, addr = server.accept(); conn.settimeout(5)
                self.pool.submit(self._receive, conn, addr[0])
            except socket.timeout: pass
            except OSError: break

    def _receive(self, conn: socket.socket, host: str) -> None:
        with conn:
            if not host_on_lan(host): return
            try:
                frame = protocol.recv_frame(conn); sender = protocol.sender_id(frame)
                peer = next((p for p in self.store.peers() if p.peer_id == sender and p.trusted), None)
                if not peer: return
                body = protocol.open_frame(frame, self.private, protocol.load_public(peer.public_key))
                if body.get("type") != "TEXT": return
                message_id, text = body["id"], body["body"]
                inserted = self.store.receive(message_id, sender, peer.name, text)
                # Persist before ACK. Duplicates with identical body are ACKed again.
                ack = protocol.seal({"type":"ACK", "id":message_id}, self.private,
                                    protocol.load_public(peer.public_key))
                conn.sendall(protocol.pack_frame(ack))
                if inserted: self.on_letter(peer.name, text)
                self.on_change()
            except (OSError, EOFError, ValueError, KeyError): return

    def _send(self, letter, peer: Peer) -> None:
        now = int(time.time()*1000)
        try:
            if not host_on_lan(peer.host): raise OSError("peer is outside active LAN")
            with socket.create_connection((peer.host, protocol.PORT), timeout=3) as conn:
                conn.settimeout(5)
                frame = protocol.seal({"type":"TEXT", "id":letter.message_id, "body":letter.body},
                                      self.private, protocol.load_public(peer.public_key))
                conn.sendall(protocol.pack_frame(frame))
                ack = protocol.open_frame(protocol.recv_frame(conn), self.private,
                                          protocol.load_public(peer.public_key))
                if ack.get("type") != "ACK" or ack.get("id") != letter.message_id:
                    raise ValueError("invalid ACK")
            self.store.delivered(letter.message_id, peer.peer_id)
        except (OSError, EOFError, ValueError, KeyError):
            self.store.attempted(letter, now)
        self.on_change()

    def _clock_loop(self) -> None:
        next_hello = 0.0
        while not self.stop_event.wait(1):
            if time.monotonic() >= next_hello:
                self._announce(); next_hello = time.monotonic() + 10
            now = int(time.time()*1000); peers = {p.peer_id:p for p in self.store.peers()}
            # One due message per peer/cycle bounds offline connection pressure.
            selected: dict[str, object] = {}
            for letter in self.store.pending(now): selected.setdefault(letter.peer_id, letter)
            for peer_id, letter in list(selected.items())[:16]:
                peer = peers.get(peer_id)
                if peer: self.pool.submit(self._send, letter, peer)

