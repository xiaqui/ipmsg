package com.xiaqui.ipmsg

import android.content.Context
import android.net.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Real UDP/TCP frames on the runner, with only Android Network routing adapted for the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26,28,29,34],shadows=[TestLanNetwork::class])
class ForegroundLanTransportTest {
    private fun eventually(message: () -> String = { "" },assertion: () -> Boolean) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!assertion() && System.nanoTime()<until) Thread.sleep(10)
        assertTrue(message(),assertion())
    }
    @Test fun offVisibleDiscoversReceivesAndRetriesTextAcrossRealLifecycle() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("ipmsg.db")
        Security.insertProviderAt(TestIdentityProvider(),1)
        Shadows.shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        val remote=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val remoteId=Protocol.id(remote.public)
        val pair=Identity.load()
        // Production deliberately rejects loopback peers. Use the runner's own non-loopback
        // IPv4 for inbound LAN traffic, without weakening that protocol policy.
        val local=NetworkInterface.getNetworkInterfaces().toList().flatMap { it.interfaceAddresses }
            .first { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isMulticastAddress }
        val localAddress=local.address
        val network=ShadowNetwork.newInstance(123)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val shadow=Shadows.shadowOf(cm)
        shadow.addNetwork(network,ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,NetworkInfo.State.CONNECTED))
        val capabilities=NetworkCapabilities()
        ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(capabilities,"addTransportType",
            ClassParameter.from(Int::class.javaPrimitiveType!!,NetworkCapabilities.TRANSPORT_WIFI))
        shadow.setNetworkCapabilities(network,capabilities)
        val address=ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java,localAddress),
            ClassParameter.from(Int::class.javaPrimitiveType!!,local.networkPrefixLength.toInt()))
        val properties=LinkProperties()
        ReflectionHelpers.callInstanceMethod<Boolean>(properties,"addLinkAddress",ClassParameter.from(LinkAddress::class.java,address))
        shadow.setLinkProperties(network,properties)
        assertTrue(Protocol.onPrefix(localAddress,localAddress,local.networkPrefixLength.toInt()))
        assertTrue(cm.getNetworkCapabilities(network)!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(localAddress,cm.getLinkProperties(network)!!.linkAddresses.single().address)
        val controller=LanSessionController {
            val store=Store(context)
            object : LanSession {
                override val engine=LanEngine(context,store,pair,{})
                override fun start()=engine.start()
                override fun stop()=engine.close()
                override fun awaitStopped() { engine.awaitStopped();store.offline();store.close() }
            }
        }
        LanRuntime::class.java.getDeclaredField("controller").apply { isAccessible=true }.set(LanRuntime,controller)
        val activity=Robolectric.buildActivity(MainActivity::class.java)
        val worker=Executors.newSingleThreadExecutor()
        try {
            activity.create().start().resume().visible();controller.awaitIdle()
            eventually { controller.engine?.diagnosticReport()?.contains("受信ポート: UDP=待受中 / TCP=待受中")==true }
            assertFalse(ReceiverState.enabled(context));assertFalse(MessengerService.active)
            val hello=Protocol.hello("test LAN peer",remote,System.currentTimeMillis())
            DatagramSocket().use { it.send(DatagramPacket(hello,hello.size,localAddress,Protocol.PORT)) }
            Store(context).use { store ->
                eventually({ controller.engine?.diagnosticReport() ?: controller.problem }) { store.peers().any { it.id==remoteId } }
                assertFalse(store.peers().first { it.id==remoteId }.trusted)
                store.trust(remoteId,true)
                val incomingId=UUID.randomUUID().toString()
                Socket(localAddress,Protocol.PORT).use { socket ->
                    socket.soTimeout=5000
                    Protocol.write(DataOutputStream(socket.getOutputStream()),Protocol.seal(
                        JSONObject().put("type","TEXT").put("id",incomingId).put("body","incoming visible"),remote,pair.public))
                    val ack=Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),remote,pair.public)
                    assertEquals("ACK",ack.getString("type"));assertEquals(incomingId,ack.getString("id"))
                }
                assertEquals("incoming visible",store.letters(true).single().body)
                // The same real engine receives part of a file before the visible owner leaves.
                fun request(body: JSONObject): JSONObject {
                    var failure: Exception?=null
                    repeat(5) {
                        try {
                            return Socket(localAddress,Protocol.PORT).use { socket ->
                                socket.soTimeout=5000
                                Protocol.write(DataOutputStream(socket.getOutputStream()),Protocol.seal(body,remote,pair.public))
                                Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),remote,pair.public)
                            }
                        } catch(error: java.io.IOException) {
                            failure=error;Thread.sleep(10) // Same idempotent file request after admission races.
                        }
                    }
                    throw failure!!
                }
                val transferId=UUID.randomUUID().toString();val fileId=UUID.randomUUID().toString()
                val prefix="received before hiding".toByteArray();val bytes=prefix+" pending tail".toByteArray()
                val offer=JSONObject().put("type","FILE_OFFER").put("transferId",transferId).put("files",
                    org.json.JSONArray().put(JSONObject().put("fileId",fileId).put("name","partial.txt")
                        .put("mime","text/plain").put("size",bytes.size).put("sha256",FileContract.sha(bytes))))
                assertEquals("TIMEOUT",request(offer).getString("code"))
                val temp=java.io.File(context.filesDir,"$transferId.part").apply { writeBytes(ByteArray(0)) }
                store.acceptTransfer(transferId,remoteId,"content://test/tree/root",mapOf(fileId to temp.absolutePath))
                val chunk=JSONObject().put("type","FILE_CHUNK").put("transferId",transferId).put("fileId",fileId)
                    .put("seq",0).put("offset",0).put("data",Protocol.encode(prefix)).put("sha256",FileContract.sha(prefix))
                assertEquals(prefix.size.toLong(),request(chunk).getLong("nextOffset"))
                activity.pause().stop();controller.awaitIdle()
                assertNull(controller.engine);assertFalse(ReceiverState.enabled(context))
                val pendingId=store.enqueue(listOf(remoteId),Protocol.id(pair.public),"self","queued while hidden")
                assertEquals("PENDING",store.letters(false).single().state)
                // A distinct ephemeral port stands in for the peer's own 42124 on another device.
                ServerSocket(0).use { server ->
                    server.soTimeout=10000
                    TestLanNetwork.port=server.localPort
                    val received=worker.submit<String> {
                        server.accept().use { socket ->
                            socket.soTimeout=5000
                            val body=Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),remote,pair.public)
                            Protocol.write(DataOutputStream(socket.getOutputStream()),Protocol.seal(
                                JSONObject().put("type","ACK").put("id",body.getString("id")),remote,pair.public))
                            body.getString("body")
                        }
                    }
                    activity.start().resume();controller.awaitIdle();LanRuntime.wake()
                    assertEquals("queued while hidden",received.get(15,TimeUnit.SECONDS))
                    eventually { store.letters(false).single().state=="DELIVERED" }
                    assertEquals(pendingId,store.letters(false).single().id)
                    val resumed=request(JSONObject().put("type","FILE_RESUME").put("transferId",transferId)
                        .put("fileId",fileId).put("offset",0).put("prefixSha256",FileContract.EMPTY_SHA))
                    assertEquals(prefix.size.toLong(),resumed.getLong("nextOffset"))
                    assertEquals(FileContract.sha(prefix),resumed.getString("prefixSha256"))
                    assertArrayEquals(prefix,temp.readBytes())
                }
            }
        } finally {
            activity.pause().stop().destroy();controller.shutdown();worker.shutdownNow()
            LanRuntime::class.java.getDeclaredField("controller").apply { isAccessible=true }.set(LanRuntime,null)
            Security.removeProvider("IpmsgTestIdentity");TestLanNetwork.port=9
        }
    }
}

@Implements(Network::class)
class TestLanNetwork : ShadowNetwork() {
    companion object { @Volatile var port=9 }
    @Implementation protected fun getSocketFactory(): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress,timeout: Int) =
                super.connect(InetSocketAddress("127.0.0.1",port),timeout)
        }
        override fun createSocket(host:String,port:Int):Socket = error("unused")
        override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int):Socket = error("unused")
        override fun createSocket(host:InetAddress,port:Int):Socket = error("unused")
        override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int):Socket = error("unused")
    }
}
