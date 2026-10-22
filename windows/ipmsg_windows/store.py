"""Durable peer trust, message deduplication and bounded retry state."""
from __future__ import annotations

import sqlite3
import hashlib
import threading
import time
import uuid
from functools import wraps
from dataclasses import dataclass
from pathlib import Path

from .protocol import MESSAGE_ID, valid_text

FILE_STATES = {"QUEUED", "OFFERING", "WAITING_ACCEPT", "TRANSFERRING", "PAUSED", "CANCEL_PENDING",
               "COMPLETED", "CANCELLED", "DECLINED", "FAILED"}


@dataclass(frozen=True)
class Peer:
    peer_id: str; name: str; public_key: str; trusted: bool; host: str; seen: int
    file_capable: bool = False
    def online(self, now: int | None = None) -> bool:
        now = int(time.time() * 1000) if now is None else now
        return self.seen > 0 and 0 <= now - self.seen <= 35_000


@dataclass(frozen=True)
class Letter:
    message_id: str; peer_id: str; name: str; body: str; incoming: bool
    created: int; state: str; attempts: int; next_attempt: int


@dataclass(frozen=True)
class Transfer:
    transfer_id: str; peer_id: str; peer_name: str; incoming: bool; state: str
    created: int; updated: int; destination: str; error: str; attempts: int; next_attempt: int


@dataclass(frozen=True)
class TransferFile:
    transfer_id: str; file_id: str; name: str; mime: str; size: int; sha256: str
    source: str; temp: str; final: str; offset: int; prefix_sha256: str; state: str


def synchronized(method):
    @wraps(method)
    def wrapper(self, *args, **kwargs):
        with self.lock:
            return method(self, *args, **kwargs)
    return wrapper


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS peers(id TEXT PRIMARY KEY,name TEXT NOT NULL,pub TEXT NOT NULL,
          trusted INTEGER NOT NULL,host TEXT NOT NULL,seen INTEGER NOT NULL,files INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS letters(id TEXT NOT NULL,peer TEXT NOT NULL,name TEXT NOT NULL,
          body TEXT NOT NULL,incoming INTEGER NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(id,peer,incoming));
        CREATE INDEX IF NOT EXISTS pending_due ON letters(incoming,state,next,created);
        CREATE TABLE IF NOT EXISTS deleted_received(id TEXT NOT NULL,peer TEXT NOT NULL,
          digest TEXT NOT NULL,PRIMARY KEY(id,peer));
        CREATE TABLE IF NOT EXISTS file_transfers(id TEXT NOT NULL,peer TEXT NOT NULL,
          peer_name TEXT NOT NULL,incoming INTEGER NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,
          updated INTEGER NOT NULL,destination TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',
          attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(id,peer,incoming));
        CREATE TABLE IF NOT EXISTS transfer_files(transfer_id TEXT NOT NULL,file_id TEXT NOT NULL,
          name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,
          source TEXT NOT NULL DEFAULT '',temp TEXT NOT NULL DEFAULT '',final TEXT NOT NULL DEFAULT '',
          offset INTEGER NOT NULL DEFAULT 0,prefix TEXT NOT NULL,state TEXT NOT NULL,
          PRIMARY KEY(transfer_id,file_id));
        CREATE INDEX IF NOT EXISTS transfer_due ON file_transfers(incoming,state,next,updated);
        """)
        columns={row[1] for row in self.db.execute("PRAGMA table_info(peers)")}
        if "files" not in columns:
            with self.db: self.db.execute("ALTER TABLE peers ADD COLUMN files INTEGER NOT NULL DEFAULT 0")

    @synchronized
    def peers(self) -> list[Peer]:
        return [Peer(r["id"], r["name"], r["pub"], bool(r["trusted"]), r["host"], r["seen"], bool(r["files"]))
                for r in self.db.execute("SELECT * FROM peers ORDER BY trusted DESC,name")]

    @synchronized
    def discover(self, peer_id: str, name: str, public_key: str, host: str, now: int,
                 file_capable: bool = False) -> bool:
        old = self.db.execute("SELECT pub FROM peers WHERE id=?", (peer_id,)).fetchone()
        if old and old[0] != public_key:
            return False
        with self.db:
            self.db.execute("DELETE FROM peers WHERE trusted=0 AND seen<?", (now - 120_000,))
            candidates = self.db.execute("SELECT id,host FROM peers WHERE trusted=0 ORDER BY seen").fetchall()
            if not old and any(row["host"] == host and row["id"] != peer_id for row in candidates):
                return False
            if not old and len(candidates) >= 16:
                for row in candidates[:len(candidates)-15]:
                    self.db.execute("DELETE FROM peers WHERE id=? AND trusted=0", (row["id"],))
            self.db.execute("INSERT OR IGNORE INTO peers(id,name,pub,trusted,host,seen,files) VALUES(?,?,?,?,?,?,?)",
                            (peer_id, name, public_key, 0, host, now, int(file_capable)))
            changed = self.db.execute("UPDATE peers SET name=?,host=?,seen=?,files=? WHERE id=? AND pub=?",
                                      (name, host, now, int(file_capable), peer_id, public_key)).rowcount
        return changed == 1

    @synchronized
    def trust(self, peer_id: str, value: bool) -> None:
        with self.db: self.db.execute("UPDATE peers SET trusted=? WHERE id=?", (int(value), peer_id))

    @synchronized
    def enqueue(self, peer_ids: list[str], self_id: str, self_name: str, body: str,
                now: int | None = None) -> str:
        if not valid_text(body): raise ValueError("invalid text")
        now = int(time.time() * 1000) if now is None else now
        targets = set(peer_ids)
        known = {p.peer_id: p for p in self.peers() if p.trusted}
        if not targets or any(p != self_id and p not in known for p in targets):
            raise ValueError("untrusted recipient")
        message_id = str(uuid.uuid4())
        with self.db:
            for peer_id in targets:
                name = known[peer_id].name if peer_id in known else self_name
                state = "DELIVERED" if peer_id == self_id else "PENDING"
                self.db.execute("INSERT INTO letters VALUES(?,?,?,?,?,?,?,?,?)",
                                (message_id, peer_id, name, body, 0, now, state, 0, 0))
                if peer_id == self_id:
                    self.db.execute("INSERT INTO letters VALUES(?,?,?,?,?,?,?,?,?)",
                                    (message_id, self_id, self_name, body, 1, now, "RECEIVED", 0, 0))
        return message_id

    @synchronized
    def receive(self, message_id: str, peer_id: str, name: str, body: str,
                now: int | None = None) -> bool:
        if not MESSAGE_ID.fullmatch(message_id) or not valid_text(body): raise ValueError("invalid message")
        if not any(p.peer_id == peer_id and p.trusted for p in self.peers()):
            raise ValueError("untrusted sender")
        now = int(time.time() * 1000) if now is None else now
        with self.db:
            deleted=self.db.execute("SELECT digest FROM deleted_received WHERE id=? AND peer=?",
                                    (message_id,peer_id)).fetchone()
            if deleted:
                if deleted[0]!=hashlib.sha256(body.encode()).hexdigest(): raise ValueError("message id collision")
                return False
            old = self.db.execute("SELECT body FROM letters WHERE id=? AND peer=? AND incoming=1",
                                  (message_id, peer_id)).fetchone()
            if old:
                if old[0] != body: raise ValueError("message id collision")
                return False
            self.db.execute("INSERT INTO letters VALUES(?,?,?,?,?,?,?,?,?)",
                            (message_id, peer_id, name, body, 1, now, "RECEIVED", 0, 0))
        return True

    @synchronized
    def delete_letter(self, letter: Letter) -> None:
        with self.db:
            if letter.incoming:
                self.db.execute("INSERT OR IGNORE INTO deleted_received VALUES(?,?,?)",
                    (letter.message_id,letter.peer_id,hashlib.sha256(letter.body.encode()).hexdigest()))
            self.db.execute("DELETE FROM letters WHERE id=? AND peer=? AND incoming=?",
                            (letter.message_id,letter.peer_id,int(letter.incoming)))

    @synchronized
    def write_if_pending(self, letter: Letter, action) -> bool:
        row=self.db.execute("SELECT 1 FROM letters WHERE id=? AND peer=? AND incoming=0 AND state='PENDING'",
                            (letter.message_id,letter.peer_id)).fetchone()
        if row: action()
        return bool(row)

    @synchronized
    def letters(self, incoming: bool | None = None) -> list[Letter]:
        where, args = ("", ()) if incoming is None else (" WHERE incoming=?", (int(incoming),))
        rows = self.db.execute("SELECT * FROM letters" + where + " ORDER BY created DESC,rowid DESC", args)
        return [Letter(r["id"], r["peer"], r["name"], r["body"], bool(r["incoming"]),
                       r["created"], r["state"], r["attempts"], r["next"]) for r in rows]

    @synchronized
    def pending(self, now: int) -> list[Letter]:
        rows = self.db.execute("SELECT l.* FROM letters l JOIN peers p ON p.id=l.peer "
          "WHERE l.incoming=0 AND l.state='PENDING' AND l.next<=? AND p.trusted=1 ORDER BY l.next,l.created", (now,))
        return [Letter(r["id"], r["peer"], r["name"], r["body"], False,
                       r["created"], r["state"], r["attempts"], r["next"]) for r in rows]

    @synchronized
    def claim_pending(self, now: int, limit: int = 16) -> list[Letter]:
        """Atomically leases at most one due letter per peer before network submission."""
        due = self.pending(now)
        selected: dict[str, Letter] = {}
        for letter in due:
            selected.setdefault(letter.peer_id, letter)
        claimed = list(selected.values())[:max(0, limit)]
        with self.db:
            for letter in claimed:
                delay = min(10_000 * (1 << min(letter.attempts, 5)), 300_000)
                changed = self.db.execute("UPDATE letters SET attempts=attempts+1,next=? "
                    "WHERE id=? AND peer=? AND incoming=0 AND state='PENDING' AND next<=?",
                    (now + delay, letter.message_id, letter.peer_id, now)).rowcount
                if changed != 1:
                    raise RuntimeError("pending claim lost")
        return claimed

    @synchronized
    def attempted(self, letter: Letter, now: int) -> None:
        delay = min(10_000 * (1 << min(letter.attempts, 5)), 300_000)
        with self.db: self.db.execute("UPDATE letters SET attempts=attempts+1,next=? "
          "WHERE id=? AND peer=? AND incoming=0 AND state='PENDING'", (now + delay, letter.message_id, letter.peer_id))

    @synchronized
    def delivered(self, message_id: str, peer_id: str) -> None:
        with self.db: self.db.execute("UPDATE letters SET state='DELIVERED' "
          "WHERE id=? AND peer=? AND incoming=0", (message_id, peer_id))

    @synchronized
    def peer_file_capable(self, peer_id: str) -> bool:
        row=self.db.execute("SELECT trusted,files FROM peers WHERE id=?",(peer_id,)).fetchone()
        return bool(row and row["trusted"] and row["files"])

    @synchronized
    def create_outgoing_transfer(self, transfer_id: str, peer_id: str, peer_name: str,
                                 files: list[dict], now: int | None = None) -> None:
        now = int(time.time()*1000) if now is None else now
        if not any(p.peer_id == peer_id and p.trusted for p in self.peers()):
            raise ValueError("untrusted recipient")
        with self.db:
            self.db.execute("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            (transfer_id,peer_id,peer_name,0,"QUEUED",now,now,"","",0,0))
            for item in files:
                self.db.execute("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (transfer_id,item["fileId"],item["name"],item["mime"],item["size"],item["sha256"],
                     item["source"],"","",0,hashlib.sha256(b"").hexdigest(),"PENDING"))

    @synchronized
    def create_self_transfer(self, transfer_id: str, self_id: str, self_name: str,
                             files: list[dict], now: int | None = None) -> None:
        now=int(time.time()*1000) if now is None else now
        with self.db:
            self.db.execute("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            (transfer_id,self_id,self_name,1,"WAITING_ACCEPT",now,now,"","",0,0))
            for item in files:
                self.db.execute("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (transfer_id,item["fileId"],item["name"],item["mime"],item["size"],item["sha256"],
                     item["source"],"","",0,hashlib.sha256(b"").hexdigest(),"PENDING"))

    @synchronized
    def receive_offer(self, transfer_id: str, peer_id: str, peer_name: str,
                      files: list[dict], now: int | None = None) -> Transfer:
        now = int(time.time()*1000) if now is None else now
        with self.db:
            old=self.db.execute("SELECT * FROM file_transfers WHERE id=? AND peer=? AND incoming=1",
                                (transfer_id,peer_id)).fetchone()
            if old:
                existing=self.transfer_files(transfer_id)
                signature=[(f.file_id,f.name,f.size,f.sha256) for f in existing]
                offered=[(f["fileId"],f["name"],f["size"],f["sha256"]) for f in files]
                if signature != offered: raise ValueError("transfer id collision")
            else:
                self.db.execute("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    (transfer_id,peer_id,peer_name,1,"WAITING_ACCEPT",now,now,"","",0,0))
                for item in files:
                    self.db.execute("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        (transfer_id,item["fileId"],item["name"],item["mime"],item["size"],item["sha256"],
                         "","","",0,hashlib.sha256(b"").hexdigest(),"PENDING"))
        return self.transfer(transfer_id,peer_id,True)

    @synchronized
    def transfer(self, transfer_id: str, peer_id: str, incoming: bool) -> Transfer:
        row=self.db.execute("SELECT * FROM file_transfers WHERE id=? AND peer=? AND incoming=?",
                            (transfer_id,peer_id,int(incoming))).fetchone()
        if not row: raise KeyError("unknown transfer")
        return Transfer(row["id"],row["peer"],row["peer_name"],bool(row["incoming"]),row["state"],
                        row["created"],row["updated"],row["destination"],row["error"],
                        row["attempts"],row["next"])

    @synchronized
    def transfers(self, incoming: bool | None = None) -> list[Transfer]:
        where,args=("",()) if incoming is None else (" WHERE incoming=?",(int(incoming),))
        return [Transfer(r["id"],r["peer"],r["peer_name"],bool(r["incoming"]),r["state"],
                         r["created"],r["updated"],r["destination"],r["error"],r["attempts"],r["next"])
                for r in self.db.execute("SELECT * FROM file_transfers"+where+" ORDER BY updated DESC",args)]

    @synchronized
    def transfer_files(self, transfer_id: str) -> list[TransferFile]:
        return [TransferFile(r["transfer_id"],r["file_id"],r["name"],r["mime"],r["size"],r["sha256"],
                             r["source"],r["temp"],r["final"],r["offset"],r["prefix"],r["state"])
                for r in self.db.execute("SELECT * FROM transfer_files WHERE transfer_id=? ORDER BY rowid",
                                         (transfer_id,))]

    @synchronized
    def accept_transfer(self, transfer_id: str, peer_id: str, destination: str,
                        temp_paths: dict[str,str]) -> None:
        now=int(time.time()*1000)
        with self.db:
            changed=self.db.execute("UPDATE file_transfers SET state='TRANSFERRING',destination=?,updated=?,error='' "
                "WHERE id=? AND peer=? AND incoming=1 AND state IN ('WAITING_ACCEPT','PAUSED')",
                (destination,now,transfer_id,peer_id)).rowcount
            if changed != 1: raise ValueError("transfer cannot be accepted")
            for file_id,temp in temp_paths.items():
                self.db.execute("UPDATE transfer_files SET temp=? WHERE transfer_id=? AND file_id=?",
                                (temp,transfer_id,file_id))

    @synchronized
    def set_transfer_state(self, transfer_id: str, peer_id: str, incoming: bool, state: str,
                           error: str = "", next_attempt: int | None = None) -> None:
        if state not in FILE_STATES: raise ValueError("invalid transfer state")
        now=int(time.time()*1000)
        with self.db:
            self.db.execute("UPDATE file_transfers SET state=?,error=?,updated=?,next=COALESCE(?,next),"
                "attempts=CASE WHEN ?=0 THEN 0 ELSE attempts END WHERE id=? AND peer=? AND incoming=?",
                (state,error,now,next_attempt,next_attempt,transfer_id,peer_id,int(incoming)))

    @synchronized
    def update_file_progress(self, transfer_id: str, file_id: str, offset: int, prefix: str,
                             state: str = "TRANSFERRING", final: str = "") -> None:
        with self.db:
            self.db.execute("UPDATE transfer_files SET offset=?,prefix=?,state=?,final=CASE WHEN ?='' THEN final ELSE ? END "
                "WHERE transfer_id=? AND file_id=?",(offset,prefix,state,final,final,transfer_id,file_id))
            self.db.execute("UPDATE file_transfers SET updated=?,next=0,attempts=0,error='' WHERE id=?",
                            (int(time.time()*1000),transfer_id))

    @synchronized
    def due_transfers(self, now: int, limit: int = 2) -> list[Transfer]:
        rows=self.db.execute("SELECT * FROM file_transfers WHERE incoming=0 AND state IN "
            "('QUEUED','OFFERING','WAITING_ACCEPT','TRANSFERRING','PAUSED','CANCEL_PENDING') AND next<=? ORDER BY next,created LIMIT ?",
            (now,max(0,limit))).fetchall()
        result=[]
        with self.db:
            for r in rows:
                delay=min(10_000*(1<<min(r["attempts"],5)),300_000)
                self.db.execute("UPDATE file_transfers SET attempts=attempts+1,next=? WHERE id=? AND peer=? AND incoming=0",
                                (now+delay,r["id"],r["peer"]))
                result.append(Transfer(r["id"],r["peer"],r["peer_name"],False,r["state"],r["created"],
                    r["updated"],r["destination"],r["error"],r["attempts"],r["next"]))
        return result


