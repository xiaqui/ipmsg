package com.xiaqui.ipmsg

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class FileTransferRuntimeTest {
    private lateinit var store:Store
    private lateinit var peer:Peer
    private lateinit var manager:FileTransferManager
    private lateinit var context:android.content.Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("ipmsg.db");store=Store(context)
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val hello=Protocol.readHello(Protocol.hello("peer",pair,100000),100000)
        store.discover(hello,"192.168.1.2",100000);store.trust(hello.id,true)
        peer=store.peers().first();manager=FileTransferManager(context,store,{},{_,_->})
    }
    @After fun close() { store.close() }
    private fun metadata(bytes:ByteArray,name:String="資料.zip")=FileMeta(UUID.randomUUID().toString(),name,
        "application/zip",bytes.size.toLong(),FileContract.sha(bytes))
    private fun offer(id:String,file:FileMeta)=JSONObject().put("type","FILE_OFFER").put("transferId",id)
        .put("files",JSONArray().put(JSONObject().put("fileId",file.fileId).put("name",file.name)
            .put("mime",file.mime).put("size",file.size).put("sha256",file.sha256)))

    @Test fun consentChunkDuplicateResumeAndCancelAreDurable() {
        val bytes="Android ↔ Windows file".toByteArray();val file=metadata(bytes);val id=UUID.randomUUID().toString()
        assertEquals("TIMEOUT",manager.handle(peer,offer(id,file)).getString("code"))
        val waiting=store.transfer(id,peer.id,true);assertEquals("WAITING_ACCEPT",waiting.state)
        val temp=java.io.File(context.filesDir,"$id.part").apply { writeBytes(ByteArray(0)) }
        store.acceptTransfer(id,peer.id,Uri.parse("content://example/tree/root").toString(),mapOf(file.fileId to temp.absolutePath))
        val accepted=manager.handle(peer,offer(id,file));assertEquals("FILE_ACCEPT",accepted.getString("type"))
        val chunk=JSONObject().put("type","FILE_CHUNK").put("transferId",id).put("fileId",file.fileId)
            .put("seq",0).put("offset",0).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))
            .put("sha256",FileContract.sha(bytes))
        val ack=manager.handle(peer,chunk);assertEquals(bytes.size.toLong(),ack.getLong("nextOffset"))
        assertEquals(ack.toString(),manager.handle(peer,chunk).toString())
        val resume=manager.handle(peer,JSONObject().put("type","FILE_RESUME").put("transferId",id)
            .put("fileId",file.fileId).put("offset",0).put("prefixSha256",FileContract.EMPTY_SHA))
        assertEquals(bytes.size.toLong(),resume.getLong("nextOffset"))
        manager.decline(store.transfer(id,peer.id,true))
        val cancelled=manager.handle(peer,JSONObject().put("type","FILE_RESUME").put("transferId",id)
            .put("fileId",file.fileId).put("offset",0).put("prefixSha256",FileContract.EMPTY_SHA))
        assertEquals("CANCELLED",cancelled.getString("code"))
    }

    @Test fun metadataLimitsRejectPathsCountsAndOversizeWithoutAllocation() {
        assertFalse(FileContract.validName("../evil"));assertFalse(FileContract.validName("CON.txt"))
        assertFalse(FileContract.validName("tail. "));assertTrue(FileContract.validName("日本語.pdf"))
        val valid=(0 until 32).map { FileMeta(UUID.randomUUID().toString(),"$it.bin","application/octet-stream",0,FileContract.EMPTY_SHA) }
        FileContract.validate(valid)
        assertThrows(IllegalArgumentException::class.java) { FileContract.validate(valid+metadata(ByteArray(0),"extra.bin")) }
        assertThrows(IllegalArgumentException::class.java) { FileContract.validate(listOf(
            FileMeta(UUID.randomUUID().toString(),"huge.bin","application/octet-stream",FileContract.MAX_FILE+1,FileContract.EMPTY_SHA))) }
    }

    @Test fun legacyHelloWithoutCapsRemainsReadableAndNewHelloAdvertisesFiles() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val current=Protocol.readHello(Protocol.hello("new",pair,100000),100000)
        assertTrue(Protocol.FILE_CAPABILITY in current.capabilities)
        val body=JSONObject().put("v",1).put("name","old").put("key",Protocol.encode(pair.public.encoded)).put("time",100000).toString()
        val legacy=JSONObject().put("body",body).put("sig",Protocol.sign(body,pair.private)).toString().toByteArray()
        assertTrue(Protocol.readHello(legacy,100000).capabilities.isEmpty())
    }
}
