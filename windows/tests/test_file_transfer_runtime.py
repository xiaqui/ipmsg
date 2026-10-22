import hashlib
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from ipmsg_windows.file_transfer import (CHUNK_BYTES, FileTransferCore, MAX_FILE_BYTES,
                                         accept_to_directory, queue_paths, valid_name)
from ipmsg_windows.store import Store


class FileTransferRuntimeTest(unittest.TestCase):
    def stores(self, folder):
        sender=Store(Path(folder)/"sender.sqlite");receiver=Store(Path(folder)/"receiver.sqlite")
        sender_id="a"*64;receiver_id="b"*64
        sender.discover(receiver_id,"receiver","key-b","192.168.1.2",1,True);sender.trust(receiver_id,True)
        receiver.discover(sender_id,"sender","key-a","192.168.1.1",1,True);receiver.trust(sender_id,True)
        return sender,receiver,sender_id,receiver_id

    def test_streaming_offer_consent_resume_duplicate_and_complete(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"長い名前-資料.zip";payload=(b"0123456789abcdef"*5000)+b"tail"
            source.write_bytes(payload)
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            sending=FileTransferCore(sender);receiving=FileTransferCore(receiver)
            sender_peer=receiver.peers()[0];receiver_peer=sender.peers()[0]
            def exchange(message):return receiving.handle(sender_peer,message)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            self.assertEqual("OFFERING",sender.transfer(transfer_id,receiver_id,False).state)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            incoming=receiver.transfer(transfer_id,sender_id,True)
            self.assertEqual("WAITING_ACCEPT",incoming.state)
            destination=Path(folder)/"received";destination.mkdir();accept_to_directory(receiver,incoming,str(destination))
            for _ in range(20):
                transfer=sender.transfer(transfer_id,receiver_id,False)
                if transfer.state=="COMPLETED":break
                sending.process(transfer,receiver_peer,exchange)
            self.assertEqual("COMPLETED",sender.transfer(transfer_id,receiver_id,False).state)
            self.assertEqual("COMPLETED",receiver.transfer(transfer_id,sender_id,True).state)
            saved=next(destination.iterdir())
            self.assertEqual(payload,saved.read_bytes())
            self.assertEqual(hashlib.sha256(payload).hexdigest(),hashlib.sha256(saved.read_bytes()).hexdigest())
            sender.db.close();receiver.db.close()

    def test_metadata_path_limits_and_old_peer_gate(self):
        self.assertFalse(valid_name("../evil"));self.assertFalse(valid_name("CON.txt"))
        self.assertFalse(valid_name("tail. "));self.assertTrue(valid_name("資料.pdf"))
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder)/"db.sqlite");peer="b"*64
            store.discover(peer,"old","key","192.168.1.2",1,False);store.trust(peer,True)
            source=Path(folder)/"zero.bin";source.write_bytes(b"")
            with self.assertRaises(ValueError):queue_paths(store,[peer],[str(source)])
            store.db.close()

    def test_multiple_recipients_and_space_gate_are_independent(self):
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder)/"db.sqlite");one="b"*64;two="d"*64
            for peer in (one,two):
                store.discover(peer,peer[:4],"key-"+peer[:4],"192.168.1.2",1,True);store.trust(peer,True)
            source=Path(folder)/"multi.apk";source.write_bytes(b"apk")
            ids=queue_paths(store,[one,two],[str(source)])
            self.assertEqual(2,len(set(ids)))
            self.assertEqual({one,two},{t.peer_id for t in store.transfers(False)})
            incoming=store.receive_offer(str(__import__("uuid").uuid4()),one,"peer",[{
                "fileId":str(__import__("uuid").uuid4()),"name":"large.zip","mime":"application/zip",
                "size":MAX_FILE_BYTES,"sha256":"0"*64}])
            destination=Path(folder)/"save";destination.mkdir()
            usage=__import__("collections").namedtuple("usage","total used free")(100,100,0)
            with mock.patch("ipmsg_windows.file_transfer.shutil.disk_usage",return_value=usage),self.assertRaises(OSError):
                accept_to_directory(store,incoming,str(destination))
            store.db.close()

    def test_chunk_constant_keeps_plain_frame_bounded(self):
        encoded=((CHUNK_BYTES+2)//3)*4
        self.assertLess(encoded+4096,52_000)

    def test_self_delivery_still_requires_destination_consent(self):
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder)/"self.sqlite");self_id="c"*64
            source=Path(folder)/"自分用.pdf";source.write_bytes(b"self-file")
            transfer_id=queue_paths(store,[self_id],[str(source)],self_id,"Windows")[0]
            transfer=store.transfer(transfer_id,self_id,True)
            self.assertEqual("WAITING_ACCEPT",transfer.state)
            destination=Path(folder)/"saved";destination.mkdir()
            accept_to_directory(store,transfer,str(destination))
            self.assertEqual("COMPLETED",store.transfer(transfer_id,self_id,True).state)
            self.assertEqual(b"self-file",(destination/"自分用.pdf").read_bytes())
            store.db.close()

    def test_cancel_is_terminal_on_both_sides(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"cancel.bin";source.write_bytes(b"x"*100)
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            sending=FileTransferCore(sender);receiving=FileTransferCore(receiver)
            sender_peer=receiver.peers()[0];receiver_peer=sender.peers()[0]
            exchange=lambda message:receiving.handle(sender_peer,message)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            receiver.transfer(transfer_id,sender_id,True)
            receiver.set_transfer_state(transfer_id,sender_id,True,"DECLINED","CANCELLED")
            answer=receiving.handle(sender_peer,{"type":"FILE_RESUME","transferId":transfer_id,
                "fileId":receiver.transfer_files(transfer_id)[0].file_id,"offset":0,
                "prefixSha256":hashlib.sha256(b"").hexdigest()})
            self.assertEqual("CANCELLED",answer["code"])
            sender.set_transfer_state(transfer_id,receiver_id,False,"CANCEL_PENDING","CANCELLED",0)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            self.assertEqual("CANCELLED",sender.transfer(transfer_id,receiver_id,False).state)
            sender.db.close();receiver.db.close()

    def test_lost_chunk_ack_restarts_from_receiver_offset(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"resume.bin";source.write_bytes(b"r"*(CHUNK_BYTES+9))
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            sending=FileTransferCore(sender);receiving=FileTransferCore(receiver)
            sender_peer=receiver.peers()[0];receiver_peer=sender.peers()[0]
            exchange=lambda message:receiving.handle(sender_peer,message)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            incoming=receiver.transfer(transfer_id,sender_id,True);destination=Path(folder)/"out";destination.mkdir()
            accept_to_directory(receiver,incoming,str(destination))
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)  # accept
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)  # resume
            def lose_ack(message):
                receiving.handle(sender_peer,message)
                raise EOFError("lost ACK")
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,lose_ack)
            self.assertEqual(0,sender.transfer_files(transfer_id)[0].offset)
            self.assertEqual(CHUNK_BYTES,receiver.transfer_files(transfer_id)[0].offset)
            restarted=FileTransferCore(sender)
            for _ in range(10):
                transfer=sender.transfer(transfer_id,receiver_id,False)
                if transfer.state=="COMPLETED":break
                restarted.process(transfer,receiver_peer,exchange)
            self.assertEqual("COMPLETED",sender.transfer(transfer_id,receiver_id,False).state)
            self.assertEqual(source.read_bytes(),(destination/"resume.bin").read_bytes())
            sender.db.close();receiver.db.close()


    def test_corrupted_temp_file_is_rejected_at_final_completion(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"original.bin";source.write_bytes(b"A"*128)
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            sending=FileTransferCore(sender);receiving=FileTransferCore(receiver)
            sender_peer=receiver.peers()[0];receiver_peer=sender.peers()[0]
            exchange=lambda message:receiving.handle(sender_peer,message)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            destination=Path(folder)/"received";destination.mkdir()
            accept_to_directory(receiver,receiver.transfer(transfer_id,sender_id,True),str(destination))
            for _ in range(8):
                file=sender.transfer_files(transfer_id)[0]
                if file.offset==file.size:break
                sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            target=receiver.transfer_files(transfer_id)[0]
            self.assertEqual(target.size,target.offset)
            Path(target.temp).write_bytes(b"B"*target.size)
            answer=receiving.handle(sender_peer,{"type":"FILE_COMPLETE","transferId":transfer_id,
                "fileId":target.file_id,"size":target.size,"sha256":target.sha256})
            self.assertEqual("HASH_MISMATCH",answer["code"])
            self.assertFalse((destination/"original.bin").exists())
            sender.db.close();receiver.db.close()

    def test_cancel_during_caps_response_cannot_restart_transfer(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"cancel-race.bin";source.write_bytes(b"data")
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            core=FileTransferCore(sender);receiver_peer=sender.peers()[0]
            def cancel_while_waiting(message):
                sender.set_transfer_state(transfer_id,receiver_id,False,"CANCEL_PENDING","CANCELLED",0)
                return {"type":"CAPS","requestId":message["requestId"]}
            core.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,cancel_while_waiting)
            self.assertEqual("CANCEL_PENDING",sender.transfer(transfer_id,receiver_id,False).state)
            sender.db.close();receiver.db.close()

    def test_lost_complete_ack_after_restart_can_finish(self):
        with tempfile.TemporaryDirectory() as folder:
            sender,receiver,sender_id,receiver_id=self.stores(folder)
            source=Path(folder)/"finished.bin";source.write_bytes(b"finished")
            transfer_id=queue_paths(sender,[receiver_id],[str(source)])[0]
            sending=FileTransferCore(sender);receiving=FileTransferCore(receiver)
            sender_peer=receiver.peers()[0];receiver_peer=sender.peers()[0]
            exchange=lambda message:receiving.handle(sender_peer,message)
            destination=Path(folder)/"received";destination.mkdir()
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            accept_to_directory(receiver,receiver.transfer(transfer_id,sender_id,True),str(destination))
            for _ in range(8):
                current=sender.transfer_files(transfer_id)[0]
                if current.offset==current.size:break
                sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            def lost_complete_ack(message):
                self.assertEqual("FILE_COMPLETE",message["type"])
                receiving.handle(sender_peer,message)
                raise EOFError("ACK lost")
            sending.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,lost_complete_ack)
            restarted=FileTransferCore(sender)
            restarted.process(sender.transfer(transfer_id,receiver_id,False),receiver_peer,exchange)
            self.assertEqual("COMPLETED",sender.transfer(transfer_id,receiver_id,False).state)
            sender.db.close();receiver.db.close()



if __name__ == "__main__":unittest.main()
