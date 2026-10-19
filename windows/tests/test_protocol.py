import json
import os
import socket
import sys
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import serialization

sys.path.insert(0, str(Path(__file__).parents[1]))
from ipmsg_windows import protocol


FIXTURE = Path(__file__).parents[2] / "app/src/test/resources/protocol-v1-windows-vector.json"


class ProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.vector = json.loads(FIXTURE.read_text(encoding="utf-8"))
        cls.sender = serialization.load_pem_private_key(cls.vector["senderPrivateTestOnly"].encode(), None)
        cls.recipient = serialization.load_pem_private_key(cls.vector["recipientPrivateTestOnly"].encode(), None)

    def test_shared_android_windows_vector(self):
        hello = protocol.read_hello(self.vector["hello"].encode(), self.vector["now"])
        self.assertEqual(self.vector["senderId"], hello.peer_id)
        body = protocol.open_frame(self.vector["textFrame"].encode(), self.recipient,
                                   self.sender.public_key())
        self.assertEqual({"type":"TEXT", "id":self.vector["messageId"], "body":"Android ↔ Windows 日本語"},
                         {k:body[k] for k in ("type","id","body")})

    def test_tamper_stale_wrong_recipient_rejected(self):
        with self.assertRaises(ValueError):
            protocol.read_hello(self.vector["hello"].encode(), self.vector["now"] + 120_001)
        frame = bytearray(self.vector["textFrame"].encode()); frame[len(frame)//2] ^= 1
        with self.assertRaises(Exception): protocol.open_frame(bytes(frame), self.recipient, self.sender.public_key())
        with self.assertRaises(Exception): protocol.open_frame(self.vector["textFrame"].encode(), self.sender, self.sender.public_key())

    def test_round_trip_ack_and_bounded_frame(self):
        frame = protocol.seal({"type":"ACK", "id":self.vector["messageId"]}, self.recipient,
                              self.sender.public_key())
        decoded = protocol.open_frame(frame, self.sender, self.recipient.public_key())
        self.assertEqual("ACK", decoded["type"])
        left,right=socket.socketpair()
        try:
            right.sendall(protocol.pack_frame(frame));self.assertEqual(frame,protocol.recv_frame(left))
            right.sendall((80_001).to_bytes(4,"big"))
            with self.assertRaises(ValueError): protocol.recv_frame(left)
        finally: left.close();right.close()

    def test_text_limits(self):
        self.assertTrue(protocol.valid_text("あ"*2730))
        self.assertFalse(protocol.valid_text("あ"*2731))
        self.assertFalse(protocol.valid_text(" \n"))

    @unittest.skipUnless(os.environ.get("ANDROID_VECTOR_PATH"), "Android-generated vector is produced in CI")
    def test_android_generated_text_ack_and_hello(self):
        vector=json.loads(Path(os.environ["ANDROID_VECTOR_PATH"]).read_text(encoding="utf-8"))
        sender=serialization.load_pem_private_key(vector["senderPrivateTestOnly"].encode(),None)
        recipient=serialization.load_pem_private_key(vector["recipientPrivateTestOnly"].encode(),None)
        self.assertEqual(vector["senderId"],protocol.read_hello(vector["hello"].encode(),vector["now"]).peer_id)
        text=protocol.open_frame(vector["textFrame"].encode(),recipient,sender.public_key())
        self.assertEqual(("TEXT",vector["messageId"],"Android生成 → Windows検証"),
                         (text["type"],text["id"],text["body"]))
        ack=protocol.open_frame(vector["ackFrame"].encode(),sender,recipient.public_key())
        self.assertEqual(("ACK",vector["messageId"]),(ack["type"],ack["id"]))


if __name__ == "__main__": unittest.main()

