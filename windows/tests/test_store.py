import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from ipmsg_windows.store import Store


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.store=Store(Path(self.temp.name)/"db.sqlite")
        self.store.discover("b"*64,"Android","key","192.168.1.12",100_000);self.store.trust("b"*64,True)
    def tearDown(self): self.store.db.close();self.temp.cleanup()

    def test_broadcast_self_dedupe_and_ack(self):
        message=self.store.enqueue(["a"*64,"b"*64,"a"*64],"a"*64,"Windows","hello",100_000)
        self.assertEqual(2,len(self.store.letters(False)));self.assertEqual(1,len(self.store.letters(True)))
        self.store.delivered(message,"b"*64)
        self.assertEqual("DELIVERED",next(x for x in self.store.letters(False) if x.peer_id=="b"*64).state)

    def test_retry_is_exponential_and_duplicate_body_is_pinned(self):
        self.store.enqueue(["b"*64],"a"*64,"Windows","hello",100_000)
        letter=self.store.pending(100_000)[0];self.store.attempted(letter,100_000)
        self.assertEqual([],self.store.pending(109_999));self.assertEqual(1,len(self.store.pending(110_000)))
        mid="00000000-0000-0000-0000-000000000001"
        self.assertTrue(self.store.receive(mid,"b"*64,"Android","same",100_000))
        self.assertFalse(self.store.receive(mid,"b"*64,"Android","same",100_001))
        with self.assertRaises(ValueError): self.store.receive(mid,"b"*64,"Android","changed",100_002)

    def test_key_change_and_untrusted_sender_rejected(self):
        self.assertFalse(self.store.discover("b"*64,"Evil","other","192.168.1.9",100_001))
        self.store.trust("b"*64,False)
        with self.assertRaises(ValueError): self.store.receive("00000000-0000-0000-0000-000000000002","b"*64,"Android","x")

if __name__ == "__main__": unittest.main()

