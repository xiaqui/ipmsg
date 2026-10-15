package com.xiaqui.ipmsg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LanEngine(private val context: Context, private val store: Store,
    private val pair: KeyPair, private val notifyLetter: (Letter) -> Unit) {
    private val running = AtomicBoolean(false)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val prefs = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    private val clock = Executors.newSingleThreadScheduledExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val activeHosts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String,Boolean>())
    private val sending = AtomicBoolean(false)
    private val sockets = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket,Boolean>())
    private val workers = ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(8))
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var tcp: ServerSocket? = null
    @Volatile private var discoveryReady = false
    @Volatile private var messagesReady = false
    @Volatile var status = "受信準備中"
        private set
    private var multicast: WifiManager.MulticastLock? = null
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { store.offline(); store.retry() }
        override fun onLost(network: Network) { store.offline() }
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
            store.offline(); store.retry()
        }
    }
    private fun networks(): List<Network> = cm.allNetworks.filter { n ->
        cm.getNetworkCapabilities(n)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true
    }
    private fun local(address: InetAddress): Boolean = networks().any { network ->
        cm.getLinkProperties(network)?.linkAddresses?.any { link ->
            Protocol.onPrefix(address,link.address,link.prefixLength)
        } == true
    }
    fun start() {
        check(running.compareAndSet(false,true))
        store.offline()
        multicast = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("ipmsg-discovery").apply { setReferenceCounted(false); acquire() }
        cm.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),callback)
        callbackRegistered = true
        Thread({ listenDiscovery() },"ipmsg-discovery").start()
        Thread({ listenMessages() },"ipmsg-receiver").start()
        clock.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { status = "通信再試行中" } },0,10,TimeUnit.SECONDS)
    }
    private fun listenDiscovery() {
        while(running.get()) {
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true; socket.bind(InetSocketAddress(Protocol.PORT))
                    socket.soTimeout = 1000; udp = socket; discoveryReady = true
                    while(running.get()) {
                        try {
                            val packet = DatagramPacket(ByteArray(4097),4097)
                            socket.receive(packet)
                            if(!local(packet.address) || networks().isEmpty()) continue
                            val hello = Protocol.readHello(packet.data.copyOf(packet.length),System.currentTimeMillis())
                            if(hello.id != Protocol.id(pair.public))
                                store.discover(hello,packet.address.hostAddress!!,System.currentTimeMillis())
                        } catch(_: java.net.SocketTimeoutException) {
                        } catch(_: IllegalArgumentException) {
                        } catch(_: org.json.JSONException) {
                        } catch(_: java.security.GeneralSecurityException) { }
                    }
                }
            } catch(_: Exception) {
                if(running.get()) SystemClock.sleep(1000)
            } finally { udp = null; discoveryReady = false }
        }
    }
    private fun listenMessages() {
        while(running.get()) {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true; server.bind(InetSocketAddress(Protocol.PORT)); server.soTimeout = 1000
                    tcp = server; messagesReady = true
                    while(running.get()) {
                        try {
                            val socket = server.accept()
                            socket.soTimeout = 5000
                            val host = socket.inetAddress.hostAddress!!
                            // Unknown endpoints cannot consume receive workers; one in-flight socket per known host.
                            if(!local(socket.inetAddress) || !store.peers().any { it.trusted && it.host == host } ||
                                !activeHosts.add(host)) { socket.close(); continue }
                            sockets.add(socket)
                            val timeout = deadlines.schedule({ runCatching { socket.close() } },3,TimeUnit.SECONDS)
                            try { workers.execute {
                                try { socket.use { if(running.get()) runCatching { receive(it) } } }
                                finally { timeout.cancel(false);sockets.remove(socket);activeHosts.remove(host) }
                            } }
                            catch(_: java.util.concurrent.RejectedExecutionException) {
                                timeout.cancel(false);sockets.remove(socket);activeHosts.remove(host);socket.close()
                            }

                        } catch(_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch(_: Exception) {
                if(running.get()) SystemClock.sleep(1000)
            } finally { tcp = null; messagesReady = false }
        }
    }
    private fun tick() {
        if(!running.get()) return
        val nets = networks()
        if(nets.isEmpty()) { status = "Wi-Fi/LAN未接続・送信は保留"; return }
        val hello = Protocol.hello(prefs.getString("name",android.os.Build.MODEL)!!,pair,System.currentTimeMillis())
        nets.forEach { network ->
            runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket); socket.broadcast = true
                    val targets = mutableSetOf("255.255.255.255")
                    cm.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                        val bytes = link.address.address
                        if(bytes.size == 4 && link.prefixLength in 1..30) {
                            val ip = bytes.fold(0L) { n,b -> (n shl 8) or (b.toLong() and 255) }
                            val mask = (0xffffffffL shl (32-link.prefixLength)) and 0xffffffffL
                            val broad = (ip and mask) or (mask xor 0xffffffffL)
                            targets.add((3 downTo 0).joinToString(".") { ((broad shr (it*8)) and 255).toString() })
                        }
                    }
                    targets.forEach { address ->
                        runCatching { socket.send(DatagramPacket(hello,hello.size,InetAddress.getByName(address),Protocol.PORT)) }
                    }
                }
            }
        }
        status = if(!discoveryReady || !messagesReady) "受信用ポート42124を準備／再試行中" else "受信待機中・承認済み " + store.peers().count { it.trusted && it.online(System.currentTimeMillis()) } + "台"
        // Never block discovery heartbeats behind slow/offline recipients.
        if(sending.compareAndSet(false,true)) {
            try { sender.execute {
                try {
                    val available = store.peers().filter { it.trusted && it.online(System.currentTimeMillis()) }.map { it.id }.toSet()
                    store.pending(System.currentTimeMillis()).filter { it.peer in available }.take(16).forEach { letter ->
                        if(!running.get()) return@execute
                        val peer = store.peers().find { it.id == letter.peer && it.trusted && it.online(System.currentTimeMillis()) }
                            ?: return@forEach
                        store.attempted(letter,System.currentTimeMillis())
                        runCatching { send(letter,peer,networks()) }
                    }
                } finally { sending.set(false) }
            } } catch(_: java.util.concurrent.RejectedExecutionException) { sending.set(false) }
        }
    }

    private fun send(letter: Letter,peer: Peer,nets: List<Network>) {
        val recipient = Protocol.key(peer.key)
        val request = Protocol.seal(JSONObject().put("type","TEXT").put("id",letter.id)
            .put("body",letter.body),pair,recipient)
        // Each recipient and each delivery has its own ACK, including broadcasts.
        nets.forEach { network ->
            if(!running.get()) return
            val success = runCatching {
                val connection = network.socketFactory.createSocket()
                sockets.add(connection)
                val timeout = deadlines.schedule({ runCatching { connection.close() } },8,TimeUnit.SECONDS)
                try { connection.use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(peer.host,Protocol.PORT),3000)
                    require(running.get() && store.peers().any { it.id == peer.id && it.trusted })
                    Protocol.write(DataOutputStream(socket.getOutputStream()),request)
                    val ack = Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),pair,recipient)
                    require(ack.getString("type") == "ACK" && ack.getString("id") == letter.id)
                    require(store.peers().any { it.id == peer.id && it.trusted })
                    store.delivered(letter.id,peer.id)
                } } finally { timeout.cancel(false);sockets.remove(connection) }
            }.isSuccess
            if(success) return
        }
    }
    private fun receive(socket: Socket) {
        val bytes = Protocol.read(DataInputStream(socket.getInputStream()))
        val id = Protocol.sender(bytes)
        val peer = store.peers().find { it.id == id && it.trusted } ?: return
        val body = Protocol.open(bytes,pair,Protocol.key(peer.key))
        require(body.getString("type") == "TEXT")
        val messageId = body.getString("id")
        val text = body.getString("body")
        val fresh = store.receive(messageId,peer.id,peer.name,text)
        if(fresh) runCatching { notifyLetter(Letter(messageId,peer.id,peer.name,text,true,
            System.currentTimeMillis(),"RECEIVED",0,0)) }
        Protocol.write(DataOutputStream(socket.getOutputStream()),
            Protocol.seal(JSONObject().put("type","ACK").put("id",messageId),pair,Protocol.key(peer.key)))
    }
    fun close() {
        running.set(false); udp?.close(); tcp?.close()
        clock.shutdownNow(); sender.shutdownNow(); workers.shutdownNow(); deadlines.shutdownNow()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        if(callbackRegistered) runCatching { cm.unregisterNetworkCallback(callback) }
        multicast?.let { if(it.isHeld) it.release() }
        store.offline()
    }
}
