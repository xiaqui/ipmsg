package com.xiaqui.ipmsg

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.net.ServerSocket
import java.net.Socket
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class MessengerTest {
    private lateinit var store: Store
    private val alice = pair()
    private val bob = pair()
    private val carol = pair()
    private val a get() = Protocol.id(alice.public)
    private val b get() = Protocol.id(bob.public)
    private val c get() = Protocol.id(carol.public)
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("ipmsg.db")
        store=Store(context)
    }
    @After fun cleanup() { store.close() }
    private fun discover(pair: KeyPair,name: String) {
        store.discover(Protocol.readHello(Protocol.hello(name,pair,100000),100000),"192.168.1.2",100000)
    }
    private fun trust(pair: KeyPair,name: String) { discover(pair,name);store.trust(Protocol.id(pair.public),true) }
    @Test fun m1_signedDiscoveryPinsIdentityAndExpires() {
        val hello=Protocol.hello("端末B",bob,100000)
        assertEquals(b,Protocol.readHello(hello,100000).id)
        assertThrows(IllegalArgumentException::class.java) { Protocol.readHello(hello,300001) }
        discover(bob,"端末B")
        assertFalse(store.peers().single().trusted)
        val forged=JSONObject(String(hello,Charsets.UTF_8))
        forged.put("body",forged.getString("body").replace("端末B","偽端末"))
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.readHello(forged.toString().toByteArray(Charsets.UTF_8),100000)
        }
    }
    @Test fun m1_encryptedTextAuthenticatesBothEndpoints() {
        val frame=Protocol.seal(JSONObject().put("type","TEXT").put("body","こんにちは"),alice,bob.public)
        assertFalse(String(frame,Charsets.UTF_8).contains("こんにちは"))
        assertEquals("こんにちは",Protocol.open(frame,bob,alice.public).getString("body"))
        assertThrows(Exception::class.java) { Protocol.open(frame,bob,carol.public) }
        assertThrows(Exception::class.java) { Protocol.open(frame,carol,alice.public) }
        val tampered=frame.clone();tampered[tampered.size/2]=(tampered[tampered.size/2].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { Protocol.open(tampered,bob,alice.public) }
    }
    @Test fun m2_independentBroadcastIncludesSelfExactlyOnceAndPersists() {
        trust(bob,"B");trust(carol,"C")
        val id=store.enqueue(listOf(a,b,c,a,b),a,"A","一斉送信",100000)
        assertEquals(3,store.letters(false).size)
        assertEquals(1,store.letters(true).size)
        assertEquals(2,store.pending(100000).size)
        store.delivered(id,b)
        assertEquals("DELIVERED",store.letters(false).first { it.peer==b }.state)
        assertEquals("PENDING",store.letters(false).first { it.peer==c }.state)
        store.close();store=Store(RuntimeEnvironment.getApplication())
        assertEquals(4,store.letters().size)
        assertEquals(id,store.letters(true).single().id)
    }
    @Test fun m2_unapprovedSenderAndOversizedTextAreRejected() {
        discover(bob,"B")
        assertThrows(IllegalArgumentException::class.java) { store.enqueue(listOf(b),a,"A","hello") }
        assertThrows(IllegalArgumentException::class.java) {
            store.receive("00000000-0000-0000-0000-000000000001",b,"B","hello")
        }
        assertFalse(Protocol.validText("あ".repeat(3000)))
        assertFalse(Protocol.validText(" "))
        assertTrue(store.letters().isEmpty())
    }
    @Test fun m3_ackLossRetryDeduplicatesAcrossRestartAndRevocation() {
        trust(bob,"B")
        val id="00000000-0000-0000-0000-000000000001"
        assertTrue(store.receive(id,b,"B","same"))
        store.close();store=Store(RuntimeEnvironment.getApplication())
        assertFalse(store.receive(id,b,"B","same"))
        assertEquals(1,store.letters(true).size)
        assertThrows(IllegalStateException::class.java) { store.receive(id,b,"B","changed") }
        store.trust(b,false)
        assertThrows(IllegalArgumentException::class.java) { store.receive(id,b,"B","same") }
    }
    @Test fun m3_retryBackoffAndOfflineRecoveryKeepPending() {
        trust(bob,"B")
        store.enqueue(listOf(b),a,"A","queued",100000)
        store.attempted(store.pending(100000).single(),100000)
        assertTrue(store.pending(100001).isEmpty())
        store.offline()
        assertFalse(store.peers().single().online(100000))
        store.retry()
        assertEquals(1,store.pending(100001).size)
        assertEquals("PENDING",store.letters(false).single().state)
    }
    @Test fun m3_loopbackTextAndAuthenticatedAckUseRealTcpFrames() {
        trust(alice,"A")
        val id="00000000-0000-0000-0000-000000000002"
        val server=ServerSocket(0)
        val executor=Executors.newSingleThreadExecutor()
        val receiver=executor.submit<Boolean> {
            server.accept().use { socket ->
                socket.soTimeout=3000
                val frame=Protocol.read(DataInputStream(socket.getInputStream()))
                val letter=Protocol.open(frame,bob,alice.public)
                val saved=store.receive(letter.getString("id"),a,"A",letter.getString("body"))
                Protocol.write(DataOutputStream(socket.getOutputStream()),
                    Protocol.seal(JSONObject().put("type","ACK").put("id",id),bob,alice.public))
                saved
            }
        }
        try {
            Socket("127.0.0.1",server.localPort).use { socket ->
                socket.soTimeout=3000
                Protocol.write(DataOutputStream(socket.getOutputStream()),
                    Protocol.seal(JSONObject().put("type","TEXT").put("id",id).put("body","TCPテスト"),alice,bob.public))
                val ack=Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),alice,bob.public)
                assertEquals(id,ack.getString("id"));assertEquals("ACK",ack.getString("type"))
            }
            assertTrue(receiver.get(5,TimeUnit.SECONDS))
            assertEquals("TCPテスト",store.letters(true).single().body)
        } finally { server.close();executor.shutdownNow() }
    }
    @Test fun m3_frameLimitsRejectBeforeAllocationAndDetectTruncation() {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { Protocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        val short=ByteArrayOutputStream()
        DataOutputStream(short).apply { writeInt(100);writeByte(1) }
        assertThrows(EOFException::class.java) { Protocol.read(DataInputStream(ByteArrayInputStream(short.toByteArray()))) }
    }
    @Test fun m1_activeLanPrefixIncludesCarrierGradePrivateRanges() {
        val host=java.net.InetAddress.getByName("100.64.2.3")
        assertTrue(Protocol.onPrefix(host,java.net.InetAddress.getByName("100.64.2.4"),24))
        assertFalse(Protocol.onPrefix(host,java.net.InetAddress.getByName("100.64.3.4"),24))
        assertFalse(Protocol.onPrefix(java.net.InetAddress.getByName("127.0.0.1"),host,8))
    }
    @Test fun m2_everyAcceptedEscapedBoundaryCanBeEncryptedAndFramed() {
        listOf("\"".repeat(8192),"\\".repeat(8192),("x"+"\n".repeat(8191)),"\u0000".repeat(8192)).forEach { text ->
            assertTrue(Protocol.validText(text))
            val frame=Protocol.seal(JSONObject().put("type","TEXT")
                .put("id","00000000-0000-0000-0000-000000000003").put("body",text),alice,bob.public)
            val buffer=ByteArrayOutputStream()
            Protocol.write(DataOutputStream(buffer),frame)
            val decoded=Protocol.read(DataInputStream(ByteArrayInputStream(buffer.toByteArray())))
            assertEquals(text,Protocol.open(decoded,bob,alice.public).getString("body"))
        }
    }
    @Test fun m3_discoveryNeverRestoresRevokedTrustAcrossStoreInstances() {
        trust(bob,"B")
        val second=Store(RuntimeEnvironment.getApplication())
        try {
            second.trust(b,false)
            discover(bob,"renamed B")
            assertFalse(store.peers().single().trusted)
            assertEquals("renamed B",store.peers().single().name)
            assertTrue(store.pending(Long.MAX_VALUE).isEmpty())
        } finally { second.close() }
    }

    @Test fun m2_queueFailureRollsBackSelfAndEveryRecipient() {
        trust(bob,"B")
        store.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON letters WHEN NEW.peer='$b' BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END")
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { store.enqueue(listOf(a,b),a,"A","atomic") }
        assertTrue(store.letters().isEmpty())
    }
    @Test fun m3_receiveStorageFailureCannotBeAcknowledged() {
        trust(bob,"B")
        store.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON letters BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END")
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            store.receive("00000000-0000-0000-0000-000000000004",b,"B","not saved")
        }
        assertTrue(store.letters().isEmpty())
    }

    @Test fun m3_arrivalRoutingSurvivesForegroundNotificationRecreation() {
        val context=RuntimeEnvironment.getApplication() as Context
        val foreground=MessengerNotifications.open(context,false)
        val arrival=MessengerNotifications.open(context,true)
        MessengerNotifications.open(context,false)
        assertNotEquals(foreground,arrival)
        assertFalse(org.robolectric.Shadows.shadowOf(foreground).savedIntent.getBooleanExtra("inbox",false))
        assertTrue(org.robolectric.Shadows.shadowOf(arrival).savedIntent.getBooleanExtra("inbox",false))
    }

    @Test fun m1_oneHostsCandidateFloodCannotBlockAnotherDevicesApproval() {
        trust(alice,"approved")
        repeat(64) { index ->
            val identity=pair()
            val now=100000L+index
            val hello=Protocol.readHello(Protocol.hello("candidate "+index,identity,now),now)
            store.discover(hello,"192.168.1.9",now)
        }
        assertEquals(1,store.peers().count { !it.trusted && it.host=="192.168.1.9" })
        val legitimate=Protocol.readHello(Protocol.hello("legitimate",bob,100100),100100)
        store.discover(legitimate,"192.168.1.10",100100)
        assertTrue(store.peers().any { it.id==b })
        assertTrue(store.peers().any { it.id==a && it.trusted })
        store.trust(b,true)
        assertTrue(store.peers().any { it.id==b && it.trusted })
    }
    @Test fun m1_candidateEvictionAdmitsNewPeersAndPreservesApprovedPeers() {
        trust(alice,"approved")
        repeat(20) { index ->
            val identity=pair()
            val now=100000L+index
            store.discover(Protocol.readHello(Protocol.hello("candidate "+index,identity,now),now),
                "192.168.1."+ (20+index),now)
        }
        store.discover(Protocol.readHello(Protocol.hello("new legitimate",carol,100100),100100),
            "192.168.1.99",100100)
        assertEquals(16,store.peers().count { !it.trusted })
        assertTrue(store.peers().any { it.id==a && it.trusted })
        assertTrue(store.peers().any { it.id==c })
    }


    @Test fun m3_discoveryDiagnosticsExposeValidationStageWithoutPayloads() {
        val hello=Protocol.hello("端末B",bob,100000)
        val clockError=assertThrows(Protocol.HelloValidationException::class.java) {
            Protocol.readHello(hello,300001)
        }
        assertEquals("時刻",clockError.stage)
        val forged=JSONObject(String(hello,Charsets.UTF_8))
        forged.put("body",JSONObject(forged.getString("body")).put("name","偽端末").toString())
        val signatureError=assertThrows(Protocol.HelloValidationException::class.java) {
            Protocol.readHello(forged.toString().toByteArray(Charsets.UTF_8),100000)
        }
        assertEquals("署名",signatureError.stage)
    }

    @Test fun m3_networkCallbacksPreserveChurnButClearLostPrefixMembership() {
        assertFalse(LanNetworkPolicy.clearPeersAfterLost(2))
        assertFalse(LanNetworkPolicy.clearPeersAfterLost(1))
        assertTrue(LanNetworkPolicy.clearPeersAfterLost(0))
        assertFalse(LanNetworkPolicy.prefixMembershipLost(emptySet(),setOf("192.168.1.0/24")))
        assertFalse(LanNetworkPolicy.prefixMembershipLost(setOf("192.168.1.0/24"),setOf("192.168.1.0/24")))
        val wifiAndEthernet = setOf("192.168.1.0/24","10.0.0.0/24")
        assertFalse(LanNetworkPolicy.prefixMembershipLost(
            wifiAndEthernet,setOf("10.0.0.0/24"))) // Losing Wi-Fi must not clear healthy Ethernet peers.
        assertFalse(LanNetworkPolicy.prefixMembershipLost(
            wifiAndEthernet,setOf("192.168.1.0/24"))) // Losing Ethernet must not clear healthy Wi-Fi peers.
        assertTrue(LanNetworkPolicy.prefixMembershipLost(
            setOf("192.168.1.0/24"),setOf("192.168.2.0/24")))
        assertTrue(LanNetworkPolicy.prefixMembershipLost(setOf("192.168.1.0/24"),emptySet()))
    }

    @Test fun m3_lanDiagnosticReportSeparatesDiscoveryTcpAckVpnAndOfflineQueue() {
        var now=100000L
        val diag=LanDiagnostics { now }
        diag.udpSent("192.168.1.255")
        diag.udpReceived("192.168.1.2")
        diag.helloAccepted(b,"192.168.1.2")
        diag.tcpAttempt(b,"192.168.1.2")
        diag.tcpConnected(b)
        diag.deliveryFailed(b,"ACK待機・検証","SocketTimeoutException")
        diag.tcpIncoming(b)
        diag.ackSent(b)
        diag.queue(2,1)
        now+=1000
        val report=diag.report("受信待機中",
            listOf(Peer(b,"B",Protocol.encode(bob.public.encoded),true,"192.168.1.2",100000)),
            listOf("wifi 192.168.1.3/24"),"あり（既定経路）",true,true,true,now)
        assertTrue(report.contains("UDP: 送信 1"))
        assertTrue(report.contains("探索検証: 成功 1"))
        assertTrue(report.contains("TCP送信: 試行 1 / 接続 1 / 接続失敗 0"))
        assertTrue(report.contains("TCP受信: 1"))
        assertTrue(report.contains("ACK: 受信 0 / 送信 1 / ACK・送信段階失敗 1"))
        assertTrue(report.contains("ACK待機・検証 SocketTimeoutException"))
        assertTrue(report.contains("VPN: あり（既定経路）"))
        assertTrue(report.contains("オンライン待ち 1件"))
        assertFalse(report.contains("端末B"))
    }

}
