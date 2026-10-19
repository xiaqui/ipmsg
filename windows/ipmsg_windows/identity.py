"""User-scoped identity storage. Production Windows keys are always DPAPI protected."""
from __future__ import annotations

import ctypes
import os
from ctypes import wintypes
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _dpapi(data: bytes, decrypt: bool) -> bytes:
    if os.name != "nt":
        raise RuntimeError("DPAPI is available only on Windows")
    source_buffer = ctypes.create_string_buffer(data)
    source = _Blob(len(data), ctypes.cast(source_buffer, ctypes.POINTER(ctypes.c_byte)))
    output = _Blob()
    crypt32, kernel32 = ctypes.WinDLL("crypt32", use_last_error=True), ctypes.WinDLL("kernel32", use_last_error=True)
    tail = [ctypes.POINTER(_Blob), ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD,
            ctypes.POINTER(_Blob)]
    crypt32.CryptProtectData.argtypes = [ctypes.POINTER(_Blob), wintypes.LPCWSTR, *tail]
    crypt32.CryptProtectData.restype = wintypes.BOOL
    crypt32.CryptUnprotectData.argtypes = [ctypes.POINTER(_Blob), ctypes.POINTER(wintypes.LPWSTR), *tail]
    crypt32.CryptUnprotectData.restype = wintypes.BOOL
    description = ctypes.c_wchar_p("Xiaqui IP Messenger")
    function = crypt32.CryptUnprotectData if decrypt else crypt32.CryptProtectData
    if not function(ctypes.byref(source), None if decrypt else description, None, None, None, 0,
                    ctypes.byref(output)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        kernel32.LocalFree(output.pbData)


def load_or_create(path: Path, *, allow_unprotected_test_storage: bool = False) -> rsa.RSAPrivateKey:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        protected = path.read_bytes()
        raw = protected if allow_unprotected_test_storage else _dpapi(protected, True)
        key = serialization.load_der_private_key(raw, password=None)
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size != 2048:
            raise ValueError("stored identity is not RSA-2048")
        return key
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    raw = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption())
    data = raw if allow_unprotected_test_storage else _dpapi(raw, False)
    temp = path.with_suffix(".tmp")
    temp.write_bytes(data)
    os.replace(temp, path)
    return key


def default_data_dir() -> Path:
    return Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "Xiaqui" / "IPMessenger"

