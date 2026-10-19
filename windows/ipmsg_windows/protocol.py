"""Android protocol-v1 compatible wire codec. No plaintext is sent in discovery."""
from __future__ import annotations

import base64
import hashlib
import json
import re
import struct
import time
from dataclasses import dataclass
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidSignature

PORT = 42124
MAX_FRAME = 80_000
MAX_TEXT = 8_192
MAX_PLAIN = 52_000
MESSAGE_ID = re.compile(r"[0-9a-f-]{36}\Z")
PEER_ID = re.compile(r"[0-9a-f]{64}\Z")


def _json(value: dict[str, Any]) -> str:
    # org.json.JSONObject emits compact JSON and does not escape non-ASCII text.
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _unb64(value: str) -> bytes:
    return base64.b64decode(value, validate=True)


def public_der(key: rsa.RSAPublicKey) -> bytes:
    return key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def fingerprint(key: rsa.RSAPublicKey) -> str:
    return hashlib.sha256(public_der(key)).hexdigest()


def load_public(encoded: str) -> rsa.RSAPublicKey:
    if not 300 <= len(encoded) <= 1000:
        raise ValueError("invalid public key length")
    key = serialization.load_der_public_key(_unb64(encoded))
    if not isinstance(key, rsa.RSAPublicKey) or key.key_size != 2048:
        raise ValueError("RSA-2048 is required")
    return key


def sign(text: str, private: rsa.RSAPrivateKey) -> str:
    return _b64(private.sign(text.encode(), padding.PKCS1v15(), hashes.SHA256()))


def verify(text: str, signature: str, public: rsa.RSAPublicKey) -> bool:
    try:
        public.verify(_unb64(signature), text.encode(), padding.PKCS1v15(), hashes.SHA256())
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


@dataclass(frozen=True)
class Hello:
    peer_id: str
    name: str
    public_key: str


def make_hello(name: str, private: rsa.RSAPrivateKey, now_ms: int | None = None) -> bytes:
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    public = private.public_key()
    body = _json({"v": 1, "name": name[:40], "key": _b64(public_der(public)), "time": now_ms})
    return _json({"body": body, "sig": sign(body, private)}).encode()


def read_hello(packet: bytes, now_ms: int | None = None) -> Hello:
    if len(packet) > 4096:
        raise ValueError("discovery packet too large")
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    outer = json.loads(packet)
    body = outer["body"]
    data = json.loads(body)
    if data["v"] != 1 or abs(int(data["time"]) - now_ms) > 120_000:
        raise ValueError("unsupported or stale discovery packet")
    public = load_public(data["key"])
    if not verify(body, outer["sig"], public):
        raise ValueError("invalid discovery signature")
    return Hello(fingerprint(public), str(data["name"])[:40], _b64(public_der(public)))


def seal(fields: dict[str, Any], private: rsa.RSAPrivateKey, recipient: rsa.RSAPublicKey,
         *, aes_key: bytes | None = None, iv: bytes | None = None) -> bytes:
    sender = private.public_key()
    plain_fields = dict(fields)
    # Android JSONObject.put appends these fields after caller-supplied fields.
    plain_fields.update(v=1, **{"from": fingerprint(sender), "to": fingerprint(recipient)})
    plain = _json(plain_fields).encode()
    if len(plain) > MAX_PLAIN:
        raise ValueError("plaintext too large")
    aes_key = AESGCM.generate_key(bit_length=256) if aes_key is None else aes_key
    iv = __import__("os").urandom(12) if iv is None else iv
    if len(aes_key) != 32 or len(iv) != 12:
        raise ValueError("invalid AES key or IV")
    cipher = AESGCM(aes_key).encrypt(iv, plain, None)
    wrapped = recipient.encrypt(
        aes_key,
        padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None),
    )
    payload = _json({"from": fingerprint(sender), "iv": _b64(iv), "key": _b64(wrapped), "cipher": _b64(cipher)})
    frame = _json({"payload": payload, "sig": sign(payload, private)}).encode()
    if not 1 <= len(frame) <= MAX_FRAME:
        raise ValueError("frame too large")
    return frame


def sender_id(frame: bytes) -> str:
    if len(frame) > MAX_FRAME:
        raise ValueError("frame too large")
    value = json.loads(json.loads(frame)["payload"])["from"]
    if not PEER_ID.fullmatch(value):
        raise ValueError("invalid sender")
    return value


def open_frame(frame: bytes, private: rsa.RSAPrivateKey, trusted: rsa.RSAPublicKey) -> dict[str, Any]:
    if len(frame) > MAX_FRAME:
        raise ValueError("frame too large")
    outer = json.loads(frame)
    payload = outer["payload"]
    if not verify(payload, outer["sig"], trusted):
        raise ValueError("invalid frame signature")
    data = json.loads(payload)
    if data["from"] != fingerprint(trusted):
        raise ValueError("sender/key mismatch")
    aes = private.decrypt(
        _unb64(data["key"]),
        padding.OAEP(mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA1(), label=None),
    )
    if len(aes) != 32 or len(_unb64(data["iv"])) != 12:
        raise ValueError("invalid encryption parameters")
    plain = AESGCM(aes).decrypt(_unb64(data["iv"]), _unb64(data["cipher"]), None)
    result = json.loads(plain)
    if result.get("v") != 1 or result.get("from") != fingerprint(trusted):
        raise ValueError("invalid authenticated sender")
    if result.get("to") != fingerprint(private.public_key()):
        raise ValueError("wrong recipient")
    return result


def pack_frame(frame: bytes) -> bytes:
    if not 1 <= len(frame) <= MAX_FRAME:
        raise ValueError("invalid frame length")
    return struct.pack(">I", len(frame)) + frame


def recv_frame(sock: Any) -> bytes:
    def exact(size: int) -> bytes:
        chunks = bytearray()
        while len(chunks) < size:
            part = sock.recv(size - len(chunks))
            if not part:
                raise EOFError("truncated frame")
            chunks.extend(part)
        return bytes(chunks)
    size = struct.unpack(">I", exact(4))[0]
    if not 1 <= size <= MAX_FRAME:
        raise ValueError("invalid frame length")
    return exact(size)


def valid_text(text: str) -> bool:
    return bool(text.strip()) and len(text.encode()) <= MAX_TEXT

