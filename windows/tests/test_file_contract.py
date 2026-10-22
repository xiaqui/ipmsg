"""Offline contract only; file transport is not enabled."""
import json
import base64
import hashlib
import re
import unittest
from pathlib import Path


def valid_metadata(value):
    name=value["name"];size=value["size"]
    return (type(size) is int and 0<=size<=2147483648 and bool(name)
        and len(name.encode())<=255 and name not in (".","..")
        and not any(ch in '/\\:' or ord(ch)<32 for ch in name)
        and not name.endswith((" ","."))
        and not re.fullmatch(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])",name.split(".")[0]))


class FileContractTest(unittest.TestCase):
    def test_shared_metadata_vectors(self):
        vector=json.loads((Path(__file__).parents[2]/"app/src/test/resources/file-transfer-v1.json").read_text(encoding="utf-8"))
        for case in vector["valid"]:self.assertTrue(valid_metadata(case),case)
        for case in vector["invalid"]:self.assertFalse(valid_metadata(case),case)
        self.assertLess(vector["chunkBytes"]*4//3+2048,52000)
        def chunk_valid(value):
            raw=base64.b64decode(value["data"])
            return (value["offset"]>=0 and value["offset"]==value["seq"]*32768
                and hashlib.sha256(raw).hexdigest()==value["sha256"] and len(raw)<=32768)
        self.assertTrue(chunk_valid(vector["messages"][2]))
        for case in vector["invalidChunks"]:self.assertFalse(chunk_valid(case))
        for case in vector["capabilities"]:self.assertEqual(case["supported"],"file-transfer/1" in case["caps"])
