"""Durable peer trust, message deduplication and bounded retry state."""
from __future__ import annotations

import sqlite3
import threading
import time
import uuid
from functools import wraps
from dataclasses import dataclass
from pathlib import Path

from .protocol import MESSAGE_ID, valid_text


@dataclass(frozen=True)
class Peer:
    peer_id: str; name: str; public_key: str; trusted: bool; host: str; seen: int
    def online(self, now: int | None = None) -> bool:
        now = int(time.time() * 1000) if now is None else now
        return self.seen > 0 and 0 <= now - self.seen <= 35_000


@dataclass(frozen=True)
class Letter:
    message_id: str; peer_id: str; name: str; body: str; incoming: bool
    created: int; state: str; attempts: int; next_attempt: int


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
          trusted INTEGER NOT NULL,host TEXT NOT NULL,seen INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS letters(id TEXT NOT NULL,peer TEXT NOT NULL,name TEXT NOT NULL,
          body TEXT NOT NULL,incoming INTEGER NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(id,peer,incoming));
        CREATE INDEX IF NOT EXISTS pending_due ON letters(incoming,state,next,created);
        """)

    @synchronized
    def peers(self) -> list[Peer]:
        return [Peer(r["id"], r["name"], r["pub"], bool(r["trusted"]), r["host"], r["seen"])
                for r in self.db.execute("SELECT * FROM peers ORDER BY trusted DESC,name")]

    @synchronized
    def discover(self, peer_id: str, name: str, public_key: str, host: str, now: int) -> bool:
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
            self.db.execute("INSERT OR IGNORE INTO peers VALUES(?,?,?,?,?,?)",
                            (peer_id, name, public_key, 0, host, now))
            changed = self.db.execute("UPDATE peers SET name=?,host=?,seen=? WHERE id=? AND pub=?",
                                      (name, host, now, peer_id, public_key)).rowcount
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
            old = self.db.execute("SELECT body FROM letters WHERE id=? AND peer=? AND incoming=1",
                                  (message_id, peer_id)).fetchone()
            if old:
                if old[0] != body: raise ValueError("message id collision")
                return False
            self.db.execute("INSERT INTO letters VALUES(?,?,?,?,?,?,?,?,?)",
                            (message_id, peer_id, name, body, 1, now, "RECEIVED", 0, 0))
        return True

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
    def attempted(self, letter: Letter, now: int) -> None:
        delay = min(10_000 * (1 << min(letter.attempts, 5)), 300_000)
        with self.db: self.db.execute("UPDATE letters SET attempts=attempts+1,next=? "
          "WHERE id=? AND peer=? AND incoming=0 AND state='PENDING'", (now + delay, letter.message_id, letter.peer_id))

    @synchronized
    def delivered(self, message_id: str, peer_id: str) -> None:
        with self.db: self.db.execute("UPDATE letters SET state='DELIVERED' "
          "WHERE id=? AND peer=? AND incoming=0", (message_id, peer_id))

