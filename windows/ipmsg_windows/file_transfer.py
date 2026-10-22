"""File-transfer/1 state machine shared by the Windows transport and offline tests."""
from __future__ import annotations

import base64
import hashlib
import mimetypes
import os
import re
import shutil
import uuid
from pathlib import Path
from typing import Callable

from .store import Peer, Store, Transfer, TransferFile

CAPABILITY = "file-transfer/1"
CHUNK_BYTES = 32_768
MAX_FILES = 32
MAX_FILE_BYTES = 2_147_483_648
MAX_TOTAL_BYTES = 4_294_967_296
EMPTY_SHA = hashlib.sha256(b"").hexdigest()
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
            *(f"LPT{i}" for i in range(1, 10))}


def sha256_file(path: Path, limit: int | None = None) -> tuple[str, int]:
    digest=hashlib.sha256(); count=0
    with path.open("rb") as source:
        while limit is None or count < limit:
            block=source.read(1024*1024 if limit is None else min(1024*1024,limit-count))
            if not block: break
            digest.update(block);count+=len(block)
    return digest.hexdigest(),count


def valid_name(name: str) -> bool:
    if not name or name in (".", "..") or len(name.encode("utf-8")) > 255:
        return False
    if name[-1:] in (" ", ".") or any(ord(ch) < 32 or ch in '/\\:\0' for ch in name):
        return False
    return name.split(".",1)[0].upper() not in RESERVED


def validate_files(files: list[dict]) -> None:
    if not isinstance(files,list) or not 1 <= len(files) <= MAX_FILES:
        raise ValueError("invalid file count")
    total=0; ids=set()
    for item in files:
        if not isinstance(item,dict) or not UUID_RE.fullmatch(str(item.get("fileId",""))):
            raise ValueError("invalid file id")
        if item["fileId"] in ids or not valid_name(str(item.get("name",""))):
            raise ValueError("invalid or duplicate file metadata")
        ids.add(item["fileId"])
        size=item.get("size"); digest=item.get("sha256"); mime=item.get("mime")
        if isinstance(size,bool) or not isinstance(size,int) or not 0 <= size <= MAX_FILE_BYTES:
            raise ValueError("invalid file size")
        if not isinstance(mime,str) or len(mime)>255 or not SHA_RE.fullmatch(str(digest)):
            raise ValueError("invalid file metadata")
        total+=size
    if total > MAX_TOTAL_BYTES: raise ValueError("transfer too large")


def describe_paths(paths: list[str]) -> list[dict]:
    if not 1 <= len(paths) <= MAX_FILES: raise ValueError("1..32 files required")
    result=[]
    for raw in paths:
        path=Path(raw)
        if path.is_symlink() or not path.is_file() or not valid_name(path.name):
            raise ValueError(f"unsafe source: {path.name}")
        size=path.stat().st_size
        if size>MAX_FILE_BYTES: raise ValueError("file exceeds 2 GiB")
        digest,count=sha256_file(path)
        if count != size: raise ValueError("source changed while hashing")
        result.append({"fileId":str(uuid.uuid4()),"name":path.name,
                       "mime":mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                       "size":size,"sha256":digest,"source":str(path.resolve())})
    validate_files(result)
    return result


def queue_paths(store: Store, peer_ids: list[str], paths: list[str], self_id: str = "",
                self_name: str = "") -> list[str]:
    files=describe_paths(paths); peers={p.peer_id:p for p in store.peers() if p.trusted}; created=[]
    if not peer_ids or any(peer != self_id and (peer not in peers or not peers[peer].file_capable)
                           for peer in peer_ids):
        raise ValueError("untrusted or unsupported recipient")
    for peer_id in dict.fromkeys(peer_ids):
        transfer_id=str(uuid.uuid4())
        if peer_id==self_id:store.create_self_transfer(transfer_id,self_id,self_name,files)
        else:store.create_outgoing_transfer(transfer_id,peer_id,peers[peer_id].name,files)
        created.append(transfer_id)
    return created


def accept_to_directory(store: Store, transfer: Transfer, directory: str) -> None:
    root=Path(directory).resolve()
    if not root.is_dir() or root.is_symlink(): raise ValueError("invalid destination")
    files=store.transfer_files(transfer.transfer_id)
    needed=sum(f.size-f.offset for f in files)+64*1024*1024
    if shutil.disk_usage(root).free < needed: raise OSError("NO_SPACE")
    temps={f.file_id:str(root/f".ipmsg-{transfer.transfer_id}-{f.file_id}.part") for f in files}
    for file in files:
        path=Path(temps[file.file_id])
        if path.exists() and (path.is_symlink() or not path.is_file()): raise ValueError("unsafe temp path")
        if not path.exists():
            with path.open("xb") as out: out.flush();os.fsync(out.fileno())
        elif path.stat().st_size != file.offset:
            with path.open("wb") as out:out.flush();os.fsync(out.fileno())
            store.update_file_progress(transfer.transfer_id,file.file_id,0,EMPTY_SHA,"PENDING")
    store.accept_transfer(transfer.transfer_id,transfer.peer_id,str(root),temps)
    if any(file.source for file in files):
        for file in store.transfer_files(transfer.transfer_id):
            source=Path(file.source);temp=Path(file.temp);digest=hashlib.sha256();count=0
            with source.open("rb") as incoming,temp.open("wb") as outgoing:
                while block:=incoming.read(1024*1024):
                    outgoing.write(block);digest.update(block);count+=len(block)
                    store.update_file_progress(transfer.transfer_id,file.file_id,count,digest.copy().hexdigest())
                outgoing.flush();os.fsync(outgoing.fileno())
            if count!=file.size or digest.hexdigest()!=file.sha256:raise OSError("SOURCE_CHANGED")
            final=unique_destination(root,file.name);os.replace(temp,final)
            store.update_file_progress(transfer.transfer_id,file.file_id,file.size,file.sha256,"COMPLETED",str(final))
        store.set_transfer_state(transfer.transfer_id,transfer.peer_id,True,"COMPLETED")


def unique_destination(root: Path, name: str) -> Path:
    stem,suffix=Path(name).stem,Path(name).suffix
    for index in range(10_000):
        candidate=root/(name if index==0 else f"{stem} ({index}){suffix}")
        if not candidate.exists(): return candidate
    raise OSError("too many name collisions")


class FileTransferCore:
    def __init__(self, store: Store, on_change: Callable[[],None] = lambda:None,
                 on_offer: Callable[[str,str],None] = lambda _peer,_transfer:None):
        self.store,self.on_change,self.on_offer=store,on_change,on_offer
        self._source_hashers: dict[tuple[str,str],tuple[int,object]]={}
        self._target_hashers: dict[tuple[str,str],tuple[int,object]]={}
        self._resumed:set[tuple[str,str]]=set()

    @staticmethod
    def _caps(request_id: str) -> dict:
        return {"type":"CAPS","requestId":request_id,"protocol":CAPABILITY,
                "maxFiles":MAX_FILES,"maxFileBytes":MAX_FILE_BYTES,"chunkBytes":CHUNK_BYTES}

    @staticmethod
    def _error(transfer_id: str, code: str, retryable: bool, file_id: str = "") -> dict:
        value={"type":"FILE_ERROR","transferId":transfer_id,"code":code,"retryable":retryable}
        if file_id:value["fileId"]=file_id
        return value

    def handle(self, peer: Peer, body: dict) -> dict:
        kind=body.get("type")
        if kind=="CAPS":
            request=str(body.get("requestId",""))
            if not UUID_RE.fullmatch(request) or body.get("protocol")!=CAPABILITY: raise ValueError("bad CAPS")
            return self._caps(request)
        transfer_id=str(body.get("transferId",""))
        if not UUID_RE.fullmatch(transfer_id): raise ValueError("bad transfer id")
        if kind in ("FILE_CHUNK","FILE_RESUME","FILE_COMPLETE"):
            try: existing=self.store.transfer(transfer_id,peer.peer_id,True)
            except KeyError: existing=None
            if existing and existing.state in ("CANCELLED","DECLINED"):
                return self._error(transfer_id,"CANCELLED",False,str(body.get("fileId","")))
        if kind=="FILE_OFFER": return self._offer(peer,transfer_id,body)
        if kind=="FILE_CHUNK": return self._chunk(peer,transfer_id,body)
        if kind=="FILE_RESUME": return self._resume(peer,transfer_id,body)
        if kind=="FILE_COMPLETE": return self._complete(peer,transfer_id,body)
        if kind=="FILE_CANCEL":
            self.store.set_transfer_state(transfer_id,peer.peer_id,True,"CANCELLED","CANCELLED")
            self.on_change();return self._error(transfer_id,"CANCELLED",False)
        if kind=="FILE_ACCEPT":
            transfer=self.store.transfer(transfer_id,peer.peer_id,False)
            self._apply_accept(transfer,body)
            return {"type":"FILE_RESUME","transferId":transfer_id,"fileId":body["selected"][0]["fileId"],
                    "offset":body["selected"][0]["offset"],"prefixSha256":body["selected"][0]["prefixSha256"]}
        if kind in ("FILE_CHUNK_ACK","FILE_COMPLETE_ACK","FILE_ERROR"):
            raise ValueError("unexpected response type")
        raise ValueError("unsupported file frame")

    def _offer(self, peer: Peer, transfer_id: str, body: dict) -> dict:
        files=body.get("files");validate_files(files)
        transfer=self.store.receive_offer(transfer_id,peer.peer_id,peer.name,files)
        self.on_change();self.on_offer(peer.name,transfer_id)
        if transfer.state=="DECLINED": return self._error(transfer_id,"DECLINED",False)
        if transfer.state not in ("TRANSFERRING","PAUSED","COMPLETED"):
            return self._error(transfer_id,"TIMEOUT",True)
        selected=[{"fileId":f.file_id,"offset":f.offset,"prefixSha256":f.prefix_sha256}
                  for f in self.store.transfer_files(transfer_id) if f.state!="COMPLETED"]
        return {"type":"FILE_ACCEPT","transferId":transfer_id,"selected":selected}

    def _incoming_file(self, transfer_id: str, peer_id: str, file_id: str) -> tuple[Transfer,TransferFile]:
        transfer=self.store.transfer(transfer_id,peer_id,True)
        if transfer.state not in ("TRANSFERRING","PAUSED"): raise ValueError("not accepted")
        file=next((f for f in self.store.transfer_files(transfer_id) if f.file_id==file_id),None)
        if not file or not file.temp: raise ValueError("unknown file")
        return transfer,file

    def _prefix_hasher(self, file: TransferFile, target: bool):
        cache=self._target_hashers if target else self._source_hashers;key=(file.transfer_id,file.file_id)
        old=cache.get(key)
        if old and old[0]==file.offset:return old[1].copy()
        digest=hashlib.sha256();path=Path(file.temp if target else file.source);count=0
        with path.open("rb") as stream:
            while count<file.offset:
                block=stream.read(min(1024*1024,file.offset-count))
                if not block:raise OSError("truncated file")
                digest.update(block);count+=len(block)
        cache[key]=(file.offset,digest.copy());return digest

    def _chunk(self, peer: Peer, transfer_id: str, body: dict) -> dict:
        file_id=str(body.get("fileId",""));transfer,file=self._incoming_file(transfer_id,peer.peer_id,file_id)
        offset=body.get("offset");seq=body.get("seq")
        if isinstance(offset,bool) or not isinstance(offset,int) or offset<0 or seq!=offset//CHUNK_BYTES:
            return self._error(transfer_id,"OFFSET_MISMATCH",False,file_id)
        try:data=base64.b64decode(body.get("data",""),validate=True)
        except (ValueError,TypeError):return self._error(transfer_id,"HASH_MISMATCH",False,file_id)
        if not 0<len(data)<=CHUNK_BYTES or offset+len(data)>file.size or hashlib.sha256(data).hexdigest()!=body.get("sha256"):
            return self._error(transfer_id,"HASH_MISMATCH",False,file_id)
        path=Path(file.temp)
        if offset<file.offset:
            with path.open("rb") as old:old.seek(offset);same=old.read(len(data))==data
            if not same:return self._error(transfer_id,"OFFSET_MISMATCH",False,file_id)
        elif offset!=file.offset:return self._error(transfer_id,"OFFSET_MISMATCH",False,file_id)
        else:
            digest=self._prefix_hasher(file,True);digest.update(data)
            with path.open("r+b") as out:
                out.seek(offset);out.write(data);out.flush();os.fsync(out.fileno())
            next_offset=offset+len(data);prefix=digest.hexdigest()
            self.store.update_file_progress(transfer_id,file_id,next_offset,prefix)
            self._target_hashers[(transfer_id,file_id)]=(next_offset,digest.copy())
            self.on_change()
        current=next(f for f in self.store.transfer_files(transfer_id) if f.file_id==file_id)
        return {"type":"FILE_CHUNK_ACK","transferId":transfer_id,"fileId":file_id,
                "nextOffset":current.offset,"prefixSha256":current.prefix_sha256}

    def _resume(self, peer: Peer, transfer_id: str, body: dict) -> dict:
        file_id=str(body.get("fileId",""));_transfer,file=self._incoming_file(transfer_id,peer.peer_id,file_id)
        return {"type":"FILE_CHUNK_ACK","transferId":transfer_id,"fileId":file_id,
                "nextOffset":file.offset,"prefixSha256":file.prefix_sha256}

    def _complete(self, peer: Peer, transfer_id: str, body: dict) -> dict:
        file_id=str(body.get("fileId",""));self.store.transfer(transfer_id,peer.peer_id,True)
        completed=next((f for f in self.store.transfer_files(transfer_id) if f.file_id==file_id),None)
        if completed and completed.state=="COMPLETED" and body.get("sha256")==completed.sha256:
            return {"type":"FILE_COMPLETE_ACK","transferId":transfer_id,"fileId":file_id,"sha256":completed.sha256}
        transfer,file=self._incoming_file(transfer_id,peer.peer_id,file_id)
        if body.get("size")!=file.size or body.get("sha256")!=file.sha256 or file.offset!=file.size:
            return self._error(transfer_id,"HASH_MISMATCH",False,file_id)
        if Path(file.temp).stat().st_size!=file.size or file.prefix_sha256!=file.sha256:
            return self._error(transfer_id,"HASH_MISMATCH",False,file_id)
        root=Path(transfer.destination).resolve()
        if not root.is_dir() or root.is_symlink():return self._error(transfer_id,"PERMISSION_LOST",False,file_id)
        final=unique_destination(root,file.name)
        if Path(file.temp).resolve().parent!=root:return self._error(transfer_id,"INVALID_PATH",False,file_id)
        os.replace(file.temp,final)
        self.store.update_file_progress(transfer_id,file_id,file.size,file.sha256,"COMPLETED",str(final))
        if all(item.state=="COMPLETED" for item in self.store.transfer_files(transfer_id)):
            self.store.set_transfer_state(transfer_id,peer.peer_id,True,"COMPLETED")
        self.on_change()
        return {"type":"FILE_COMPLETE_ACK","transferId":transfer_id,"fileId":file_id,"sha256":file.sha256}

    def _apply_accept(self, transfer: Transfer, body: dict) -> None:
        selected=body.get("selected")
        if not isinstance(selected,list):raise ValueError("bad accept")
        files={f.file_id:f for f in self.store.transfer_files(transfer.transfer_id)}
        if {x.get("fileId") for x in selected}!={f for f in files}:raise ValueError("partial accept unsupported")
        for item in selected:
            file=files[item["fileId"]];offset=item.get("offset");prefix=item.get("prefixSha256")
            if not isinstance(offset,int) or not 0<=offset<=file.size or not SHA_RE.fullmatch(str(prefix)):
                raise ValueError("bad accept offset")
            self.store.update_file_progress(transfer.transfer_id,file.file_id,offset,prefix)
        self.store.set_transfer_state(transfer.transfer_id,transfer.peer_id,False,"TRANSFERRING",next_attempt=0)

    def process(self, transfer: Transfer, peer: Peer, exchange: Callable[[dict],dict]) -> None:
        try:
            if not peer.trusted or not peer.file_capable:raise ValueError("UNSUPPORTED")
            if transfer.state=="CANCEL_PENDING":
                exchange({"type":"FILE_CANCEL","transferId":transfer.transfer_id,"reason":"USER_CANCELLED"})
                self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"CANCELLED","CANCELLED")
                return
            if transfer.state=="QUEUED":
                request=str(uuid.uuid4());answer=exchange(self._caps(request))
                if answer.get("type")!="CAPS" or answer.get("requestId")!=request:raise ValueError("UNSUPPORTED")
                self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"OFFERING",next_attempt=0);return
            files=self.store.transfer_files(transfer.transfer_id)
            if transfer.state in ("OFFERING","WAITING_ACCEPT"):
                offer={"type":"FILE_OFFER","transferId":transfer.transfer_id,"files":[
                    {"fileId":f.file_id,"name":f.name,"mime":f.mime,"size":f.size,"sha256":f.sha256} for f in files]}
                answer=exchange(offer)
                if answer.get("type")=="FILE_ACCEPT":self._apply_accept(transfer,answer)
                elif answer.get("type")=="FILE_ERROR" and not answer.get("retryable",False):
                    self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"DECLINED",answer.get("code","DECLINED"))
                else:self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"WAITING_ACCEPT")
                return
            current=next((f for f in files if f.state!="COMPLETED"),None)
            if current is None:
                self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"COMPLETED");return
            key=(transfer.transfer_id,current.file_id)
            if key not in self._resumed:
                answer=exchange({"type":"FILE_RESUME","transferId":transfer.transfer_id,"fileId":current.file_id,
                                 "offset":current.offset,"prefixSha256":current.prefix_sha256})
                if answer.get("type")=="FILE_ERROR" and answer.get("code") in ("CANCELLED","DECLINED"):
                    self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"CANCELLED",answer["code"]);return
                if answer.get("type")=="FILE_ERROR" and answer.get("retryable",False):return
                if answer.get("type")!="FILE_CHUNK_ACK":raise ValueError("OFFSET_MISMATCH")
                offset=answer.get("nextOffset");prefix=answer.get("prefixSha256")
                if not isinstance(offset,int) or not 0<=offset<=current.size or not SHA_RE.fullmatch(str(prefix)):
                    raise ValueError("OFFSET_MISMATCH")
                self.store.update_file_progress(transfer.transfer_id,current.file_id,offset,prefix)
                self._resumed.add(key);return
            current=next(f for f in self.store.transfer_files(transfer.transfer_id) if f.file_id==current.file_id)
            source=Path(current.source)
            if source.is_symlink() or not source.is_file() or source.stat().st_size!=current.size:
                raise ValueError("SOURCE_CHANGED")
            if current.offset<current.size:
                with source.open("rb") as stream:stream.seek(current.offset);data=stream.read(CHUNK_BYTES)
                answer=exchange({"type":"FILE_CHUNK","transferId":transfer.transfer_id,"fileId":current.file_id,
                    "seq":current.offset//CHUNK_BYTES,"offset":current.offset,
                    "data":base64.b64encode(data).decode("ascii"),"sha256":hashlib.sha256(data).hexdigest()})
                if answer.get("type")=="FILE_ERROR" and answer.get("code") in ("CANCELLED","DECLINED"):
                    self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"CANCELLED",answer["code"]);return
                if answer.get("type")=="FILE_ERROR" and answer.get("retryable",False):return
                expected=current.offset+len(data)
                if answer.get("type")!="FILE_CHUNK_ACK" or answer.get("nextOffset")!=expected:
                    raise ValueError("OFFSET_MISMATCH")
                digest=self._prefix_hasher(current,False);digest.update(data)
                if answer.get("prefixSha256")!=digest.hexdigest():raise ValueError("HASH_MISMATCH")
                self.store.update_file_progress(transfer.transfer_id,current.file_id,expected,digest.hexdigest())
                self._source_hashers[key]=(expected,digest.copy());return
            digest,count=sha256_file(source)
            if count!=current.size or digest!=current.sha256:raise ValueError("SOURCE_CHANGED")
            answer=exchange({"type":"FILE_COMPLETE","transferId":transfer.transfer_id,"fileId":current.file_id,
                             "size":current.size,"sha256":current.sha256})
            if answer.get("type")=="FILE_ERROR" and answer.get("code") in ("CANCELLED","DECLINED"):
                self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"CANCELLED",answer["code"]);return
            if answer.get("type")=="FILE_ERROR" and answer.get("retryable",False):return
            if answer.get("type")!="FILE_COMPLETE_ACK" or answer.get("sha256")!=current.sha256:
                raise ValueError("HASH_MISMATCH")
            self.store.update_file_progress(transfer.transfer_id,current.file_id,current.size,current.sha256,"COMPLETED")
            if all(f.state=="COMPLETED" for f in self.store.transfer_files(transfer.transfer_id)):
                self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,"COMPLETED")
        except (OSError,ValueError,KeyError,EOFError) as error:
            code=str(error) if str(error) in {"UNSUPPORTED","SOURCE_CHANGED","OFFSET_MISMATCH","HASH_MISMATCH"} else "IO_ERROR"
            state="FAILED" if code in {"UNSUPPORTED","SOURCE_CHANGED","HASH_MISMATCH"} else transfer.state
            self.store.set_transfer_state(transfer.transfer_id,peer.peer_id,False,state,code)
        finally:self.on_change()
