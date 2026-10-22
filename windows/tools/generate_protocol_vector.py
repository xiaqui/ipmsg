"""Regenerate the public interoperability fixture. Keys are test-only and intentionally committed."""
import json,sys
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
sys.path.insert(0,str(Path(__file__).parents[1]))
from ipmsg_windows import protocol

def pem(key):
    return key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption()).decode()

sender=rsa.generate_private_key(public_exponent=65537,key_size=2048)
recipient=rsa.generate_private_key(public_exponent=65537,key_size=2048)
now=1_800_000_000_000;message="00000000-0000-0000-0000-000000000005"
transfer="10000000-0000-4000-8000-000000000005";file_id="20000000-0000-4000-8000-000000000005"
chunk=b"Windows vector file"
fixture={"warning":"TEST KEYS ONLY - NEVER USE IN PRODUCTION","now":now,"messageId":message,
 "senderId":protocol.fingerprint(sender.public_key()),"recipientId":protocol.fingerprint(recipient.public_key()),
 "senderPublicTestOnly":protocol._b64(protocol.public_der(sender.public_key())),
 "recipientPublicTestOnly":protocol._b64(protocol.public_der(recipient.public_key())),
 "senderPrivateTestOnly":pem(sender),"recipientPrivateTestOnly":pem(recipient),
 "hello":protocol.make_hello("Windows テスト",sender,now).decode(),
 "textFrame":protocol.seal({"type":"TEXT","id":message,"body":"Android ↔ Windows 日本語"},sender,
  recipient.public_key(),aes_key=bytes(range(32)),iv=bytes(range(12))).decode(),
 "fileOfferFrame":protocol.seal({"type":"FILE_OFFER","transferId":transfer,"files":[{
  "fileId":file_id,"name":"相互運用.pdf","mime":"application/pdf","size":len(chunk),
  "sha256":__import__("hashlib").sha256(chunk).hexdigest()}]},sender,recipient.public_key()).decode(),
 "fileChunkFrame":protocol.seal({"type":"FILE_CHUNK","transferId":transfer,"fileId":file_id,
  "seq":0,"offset":0,"data":protocol._b64(chunk),"sha256":__import__("hashlib").sha256(chunk).hexdigest()},
  sender,recipient.public_key()).decode()}
target=Path(__file__).parents[2]/"app/src/test/resources/protocol-v1-windows-vector.json"
target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(fixture,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


