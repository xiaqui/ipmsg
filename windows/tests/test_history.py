import tempfile
import threading
import time
import unittest
from pathlib import Path

from ipmsg_windows.store import Store


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=Store(Path(self.temp.name)/"history.sqlite")
        self.store.discover("peer","Android","key","192.168.1.2",1)
        self.store.trust("peer",True)

    def tearDown(self):
        self.store.db.close();self.temp.cleanup()

    def test_delete_claimed_pending_does_not_send_or_revive(self):
        self.store.enqueue(["peer"],"self","Windows","本文\n🍎",1)
        letter=self.store.claim_pending(1)[0]
        self.store.delete_letter(letter)
        calls=[]
        self.assertFalse(self.store.write_if_pending(letter,lambda:calls.append(True)))
        self.store.delivered(letter.message_id,letter.peer_id)
        self.assertEqual([],calls);self.assertEqual([],self.store.letters())

    def test_deleted_receive_keeps_dedupe_without_body(self):
        mid="00000000-0000-0000-0000-000000000001"
        self.store.receive(mid,"peer","Android","本文\n🍎",1)
        self.store.delete_letter(self.store.letters(True)[0])
        self.assertFalse(self.store.receive(mid,"peer","Android","本文\n🍎",2))
        with self.assertRaises(ValueError):self.store.receive(mid,"peer","Android","changed",3)
        self.assertEqual([],self.store.letters())

    def test_delete_serializes_with_in_progress_write(self):
        self.store.enqueue(["peer"],"self","Windows","body",1)
        letter=self.store.pending(1)[0];started=threading.Event();release=threading.Event()
        sender=threading.Thread(target=lambda:self.store.write_if_pending(letter,lambda:(started.set(),release.wait(2))))
        sender.start();self.assertTrue(started.wait(1))
        deleted=threading.Event()
        worker=threading.Thread(target=lambda:(self.store.delete_letter(letter),deleted.set()))
        worker.start();time.sleep(0.02);self.assertFalse(deleted.is_set())
        release.set();sender.join(2);worker.join(2)
        self.assertTrue(deleted.is_set());self.assertEqual([],self.store.pending(100000))
