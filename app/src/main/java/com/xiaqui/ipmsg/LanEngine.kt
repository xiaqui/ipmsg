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
    private val pair: KeyPair, private val notifyLetter: (Letter) -> Unit,
    notifyFileOffer:(String,String)->Unit = { _,_-> }) {
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
    private val diagnostics = LanDiagnostics()
    private val selfId = Protocol.id(pair.public)
    private val lanPrefixes = java.util.concurrent.ConcurrentHashMap<Network,Set<String>>()
    private val lanAddresses = java.util.concurrent.ConcurrentHashMap<Network,Set<String>>()
    private val networkReconcileGate = NetworkReconcileGate()
    private val fileManager=FileTransferManager(context,store,{ scheduleTick() },notifyFileOffer)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val properties = cm.getLinkProperties(network)
            val current = properties?.let(::ipv4Prefixes).orEmpty()
            val addresses = properties?.let(::ipv4Addresses).orEmpty()
            if(current.isNotEmpty()) lanPrefixes[network] = current
            if(addresses.isNotEmpty()) lanAddresses[network] = addresses
            diagnostics.network("利用可能",network.toString()+" prefix="+current.joinToString(","))
            // A callback is not proof that all peers vanished. Keep their last-seen timestamps
            // and send an immediate hello; indiscriminate clearing caused online-state flicker.
            retryPeersOn(current); scheduleNetworkReconcile("利用可能"); scheduleTick()
        }
        override fun onLost(network: Network) {
            lanPrefixes.remove(network)
            lanAddresses.remove(network)
            diagnostics.network("切断",network.toString()+" 残りIPv4 LAN="+lanPrefixes.size)
            scheduleNetworkReconcile("切断")
            scheduleTick()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
            val previous = lanPrefixes[network]
            val previousAddresses = lanAddresses[network]
            val current = ipv4Prefixes(lp)
            val currentAddresses = ipv4Addresses(lp)
            if(current.isEmpty()) lanPrefixes.remove(network) else lanPrefixes[network] = current
            if(currentAddresses.isEmpty()) lanAddresses.remove(network) else lanAddresses[network] = currentAddresses
            diagnostics.network("構成変更",network.toString()+" prefix="+
                if(current.isEmpty()) "IPv4なし" else current.joinToString(","))
            if(previous != current || previousAddresses != currentAddresses) {
                // Detect a new local address even when its normalized subnet remains unchanged.
                // Preserve attempt history, but let a recovered/new IP retry within ten seconds
                // instead of waiting out a backoff accumulated on the old configuration.
                retryPeersOn(current)
                scheduleNetworkReconcile("構成変更")
            }
            scheduleTick()
        }
    }
    private fun scheduleTick() {
        runCatching { clock.execute { runCatching { tick() }.onFailure {
            diagnostics.network("再探索失敗",it.javaClass.simpleName)
        } } }
    }
    private fun scheduleNetworkReconcile(reason: String) =
        scheduleNetworkReconcile(reason,false,networkReconcileGate.next())
    private fun scheduleNetworkReconcile(reason: String, confirmEmpty: Boolean, generation: Long) {
        runCatching { clock.schedule(reconcile@{
            if(!networkReconcileGate.isCurrent(generation)) return@reconcile
            val snapshot=networks().mapNotNull { network ->
                cm.getLinkProperties(network)?.let(::ipv4Prefixes)?.takeIf { it.isNotEmpty() }
                    ?.let { network to it }
            }.toMap()
            val prefixes=snapshot.values.flatten().toSet()
            networkReconcileGate.runIfCurrent(generation) {
                if(running.get()) {
                    lanPrefixes.clear();lanPrefixes.putAll(snapshot)
                    lanAddresses.clear()
                    snapshot.keys.forEach { n ->
                        cm.getLinkProperties(n)?.let(::ipv4Addresses)?.takeIf { it.isNotEmpty() }
                            ?.let { lanAddresses[n] = it }
                    }
                    when {
                        prefixes.isNotEmpty() -> expirePeersOutside(prefixes,reason)
                        LanNetworkPolicy.shouldConfirmOffline(prefixes,confirmEmpty) -> {
                            store.offline();diagnostics.network("全LAN失効",reason+" 再照合済み")
                        }
                        else -> {
                            diagnostics.network("全LAN再照合",reason+" IPv4未確定")
                            scheduleNetworkReconcile(reason,true,generation)
                        }
                    }
                }
            }
        },if(confirmEmpty) 1000 else 500,TimeUnit.MILLISECONDS) }
    }
    private fun networks(): List<Network> = cm.allNetworks.filter { n ->
        cm.getNetworkCapabilities(n)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true
    }
    private fun local(address: InetAddress): Boolean = networks().any { network ->
        onNetwork(address,network)
    }
    private fun onNetwork(address: InetAddress, network: Network): Boolean =
        cm.getLinkProperties(network)?.linkAddresses?.any { link ->
            Protocol.onPrefix(address,link.address,link.prefixLength)
        } == true
    private fun reachableNetworks(host: String, candidates: List<Network>): List<Network> =
        LanNetworkPolicy.parseIpv4(host)?.let { address ->
            candidates.filter { onNetwork(address,it) }
        }.orEmpty()
    private fun expirePeersOutside(prefixes: Set<String>, reason: String) {
        if(prefixes.isEmpty()) return
        val expired=store.peers().filter { it.seen > 0 &&
            !LanNetworkPolicy.hostOnPrefixes(it.host,prefixes) }.map { it.id }
        if(expired.isNotEmpty()) {
            store.offline(expired)
            diagnostics.network("端末失効",reason+" LAN外="+expired.joinToString(",") { it.take(8) })
        }
    }
    private fun retryPeersOn(prefixes: Set<String>) {
        if(prefixes.isEmpty()) return
        val ids=store.peers().filter { it.trusted &&
            LanNetworkPolicy.hostOnPrefixes(it.host,prefixes) }.map { it.id }
        store.retry(ids)
    }
    private fun ipv4Addresses(lp: android.net.LinkProperties): Set<String> =
        lp.linkAddresses.filter { it.address is Inet4Address && it.prefixLength in 1..32 }
            .map { it.address.hostAddress + "/" + it.prefixLength }.toSet()
    private fun ipv4Prefixes(lp: android.net.LinkProperties): Set<String> =
        lp.linkAddresses.filter { it.address is Inet4Address && it.prefixLength in 1..32 }.map { link ->
            val ip = link.address.address.fold(0L) { n,b -> (n shl 8) or (b.toLong() and 255) }
            val mask = if(link.prefixLength == 32) 0xffffffffL
                else (0xffffffffL shl (32-link.prefixLength)) and 0xffffffffL
            val prefix = ip and mask
            (3 downTo 0).joinToString(".") { ((prefix shr (it*8)) and 255).toString() }+"/"+link.prefixLength
        }.toSet()
    fun start() {
        check(running.compareAndSet(false,true))
        // A recent signed discovery remains valid across a quick process/service restart and
        // expires naturally after 35 seconds. Explicit LAN loss still invalidates it below.
        diagnostics.network("サービス開始","保存済み最終発見時刻を保持")
        multicast = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("ipmsg-discovery")?.apply { setReferenceCounted(false) }
        runCatching { multicast?.acquire() }.onFailure {
            diagnostics.network("MulticastLock失敗",it.javaClass.simpleName)
            multicast = null
        }
        diagnostics.network("MulticastLock",if(multicast?.isHeld == true) "保持" else "利用不可・探索継続")
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
                        var packetHost = "?"
                        try {
                            val packet = DatagramPacket(ByteArray(4097),4097)
                            socket.receive(packet)
                            val host = packet.address.hostAddress ?: "?"
                            packetHost = host
                            if(!local(packet.address) || networks().isEmpty()) {
                                diagnostics.helloRejected(host,"有効LAN外")
                                diagnostics.udpReceived(host,UdpReceiveResult.DROPPED,"有効LAN外")
                                continue
                            }
                            val now = System.currentTimeMillis()
                            val hello = Protocol.readHello(packet.data.copyOf(packet.length),now)
                            if(hello.id == selfId) {
                                diagnostics.udpReceived(host,UdpReceiveResult.SELF,"署名OK")
                            } else {
                                if(store.discover(hello,host,now)) {
                                    diagnostics.helloAccepted(hello.id,host)
                                    store.retry(listOf(hello.id),now)
                                    diagnostics.udpReceived(host,UdpReceiveResult.PEER,
                                        "署名OK "+hello.id.take(8))
                                } else {
                                    diagnostics.helloRejected(host,"候補制限または鍵不一致")
                                    diagnostics.udpReceived(host,UdpReceiveResult.DROPPED,
                                        "候補制限または鍵不一致")
                                }
                            }
                        } catch(_: java.net.SocketTimeoutException) {
                        } catch(e: java.io.IOException) {
                            // A receive/socket failure belongs to the listener lifecycle, not to
                            // packet validation. Recreate the socket in the outer loop.
                            throw e
                        } catch(e: Protocol.HelloValidationException) {
                            diagnostics.helloRejected(packetHost,e.stage)
                            diagnostics.udpReceived(packetHost,UdpReceiveResult.DROPPED,e.stage)
                        } catch(e: Exception) {
                            val reason="処理例外 "+e.javaClass.simpleName
                            diagnostics.helloRejected(packetHost,reason)
                            diagnostics.udpReceived(packetHost,UdpReceiveResult.DROPPED,reason)
                        }
                    }
                }
            } catch(e: Exception) {
                if(running.get()) {
                    diagnostics.network("UDP待受再作成",e.javaClass.simpleName)
                    SystemClock.sleep(1000)
                }
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
                                !activeHosts.add(host)) {
                                diagnostics.network("TCP受信拒否",host)
                                socket.close(); continue
                            }
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
        if(nets.isEmpty()) {
            val pending = store.letters(false).count { it.state == "PENDING" }
            diagnostics.queue(pending,pending)
            status = "Wi-Fi/LAN未接続・送信は保留"; return
        }
        val hello = Protocol.hello(prefs.getString("name",android.os.Build.MODEL)!!,pair,System.currentTimeMillis())
        val peerSnapshot = store.peers()
        nets.forEach { network ->
            runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket); socket.broadcast = true
                    val targets = linkedMapOf("255.255.255.255" to false)
                    cm.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                        val bytes = link.address.address
                        if(bytes.size == 4 && link.prefixLength in 1..30) {
                            val ip = bytes.fold(0L) { n,b -> (n shl 8) or (b.toLong() and 255) }
                            val mask = (0xffffffffL shl (32-link.prefixLength)) and 0xffffffffL
                            val broad = (ip and mask) or (mask xor 0xffffffffL)
                            targets[(3 downTo 0).joinToString(".") { ((broad shr (it*8)) and 255).toString() }] = false
                        }
                    }
                    peerSnapshot.asSequence().filter { it.trusted }.take(32).forEach { peer ->
                        val address=LanNetworkPolicy.parseIpv4(peer.host)
                        if(address != null && onNetwork(address,network)) targets[peer.host] = true
                    }
                    targets.forEach { (address,knownPeer) ->
                        runCatching {
                            socket.send(DatagramPacket(hello,hello.size,InetAddress.getByName(address),Protocol.PORT))
                        }.onSuccess { diagnostics.udpSent(address,knownPeer) }
                            .onFailure { diagnostics.network("UDP送信失敗",address+" "+it.javaClass.simpleName) }
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        val peers = store.peers()
        val available = peers.filter { it.trusted && it.online(now) }.map { it.id }.toSet()
        val allPending = store.letters(false).filter { it.state == "PENDING" }
        diagnostics.queue(allPending.size,allPending.count { it.peer !in available })
        status = if(!discoveryReady || !messagesReady) "受信用ポート42124を準備／再試行中" else "受信待機中・承認済み " + available.size + "台"
        // Never block discovery heartbeats behind slow/offline recipients.
        if(sending.compareAndSet(false,true)) {
            try { sender.execute {
                try {
                    val currentNow=System.currentTimeMillis()
                    val activeNetworks=networks()
                    val due=LanRetryPolicy.select(store.pending(currentNow),store.peers(),currentNow,16) {
                        reachableNetworks(it,activeNetworks).isNotEmpty()
                    }.map { candidate -> DeliveryAttempt(candidate.letter,candidate.peer,
                        reachableNetworks(candidate.peer.host,activeNetworks),candidate.offlineFallback) }
                    due.forEach { attempt ->
                        if(!running.get()) return@execute
                        if(store.peers().any { it.id == attempt.peer.id && it.trusted }) {
                            store.attempted(attempt.letter,System.currentTimeMillis())
                            runCatching { send(attempt.letter,attempt.peer,attempt.networks,attempt.offlineFallback) }
                        }
                    }
                    store.dueTransfers(currentNow,2).forEach { transfer ->
                        val peer=store.peers().find { it.id==transfer.peer&&it.trusted } ?: return@forEach
                        val reachable=reachableNetworks(peer.host,activeNetworks)
                        if(reachable.isNotEmpty()) fileManager.process(transfer,peer) { body ->
                            exchangeFile(peer,reachable,body)
                        }
                    }
                } finally { sending.set(false) }
            } } catch(_: java.util.concurrent.RejectedExecutionException) { sending.set(false) }
        }
    }

    private fun send(letter: Letter,peer: Peer,nets: List<Network>,offlineFallback: Boolean) {
        val recipient = Protocol.key(peer.key)
        val request = Protocol.seal(JSONObject().put("type","TEXT").put("id",letter.id)
            .put("body",letter.body),pair,recipient)
        // Each recipient and each delivery has its own ACK, including broadcasts.
        nets.forEach { network ->
            if(!running.get()) return
            diagnostics.tcpAttempt(peer.id,peer.host,offlineFallback)
            var connected = false
            var stage = "TCP接続"
            val success = runCatching {
                val connection = network.socketFactory.createSocket()
                sockets.add(connection)
                val timeout = deadlines.schedule({ runCatching { connection.close() } },8,TimeUnit.SECONDS)
                try { connection.use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(InetSocketAddress(peer.host,Protocol.PORT),3000)
                    connected = true
                    diagnostics.tcpConnected(peer.id)
                    require(running.get() && store.peers().any { it.id == peer.id && it.trusted })
                    stage = "本文送信"
                    check(store.writeIfPending(letter) {
                        Protocol.write(DataOutputStream(socket.getOutputStream()),request)
                    }) { "削除済みの送信記録" }
                    stage = "ACK待機・検証"
                    val ack = Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),pair,recipient)
                    require(ack.getString("type") == "ACK" && ack.getString("id") == letter.id)
                    require(store.peers().any { it.id == peer.id && it.trusted })
                    diagnostics.ackReceived(peer.id)
                    stage = "配送記録"
                    store.delivered(letter.id,peer.id)
                } } finally { timeout.cancel(false);sockets.remove(connection) }
            }.onFailure {
                val reason = it.javaClass.simpleName+":"+it.message.orEmpty()
                if(connected) diagnostics.deliveryFailed(peer.id,stage,reason)
                else diagnostics.tcpFailed(peer.id,reason)
            }.isSuccess
            if(success) return
        }
    }
    private fun receive(socket: Socket) {
        val bytes = Protocol.read(DataInputStream(socket.getInputStream()))
        val id = Protocol.sender(bytes)
        val peer = store.peers().find { it.id == id && it.trusted } ?: return
        diagnostics.tcpIncoming(peer.id)
        val body = Protocol.open(bytes,pair,Protocol.key(peer.key))
        val response=if(body.getString("type") == "TEXT") {
            val messageId = body.getString("id")
            val text = body.getString("body")
            val fresh = store.receive(messageId,peer.id,peer.name,text)
            if(fresh) runCatching { notifyLetter(Letter(messageId,peer.id,peer.name,text,true,
                System.currentTimeMillis(),"RECEIVED",0,0)) }
            diagnostics.ackSent(peer.id)
            JSONObject().put("type","ACK").put("id",messageId)
        } else fileManager.handle(peer,body)
        Protocol.write(DataOutputStream(socket.getOutputStream()),Protocol.seal(response,pair,Protocol.key(peer.key)))
    }
    private fun exchangeFile(peer:Peer,nets:List<Network>,body:JSONObject):JSONObject {
        var failure:Throwable?=null
        nets.forEach { network ->
            if(!running.get()) throw IllegalStateException("stopped")
            try {
                val connection=network.socketFactory.createSocket();sockets.add(connection)
                val timeout=deadlines.schedule({ runCatching { connection.close() } },8,TimeUnit.SECONDS)
                try { connection.use { socket ->
                    socket.soTimeout=5000;socket.connect(InetSocketAddress(peer.host,Protocol.PORT),3000)
                    require(store.peers().any { it.id==peer.id&&it.trusted })
                    Protocol.write(DataOutputStream(socket.getOutputStream()),Protocol.seal(body,pair,Protocol.key(peer.key)))
                    val answer=Protocol.open(Protocol.read(DataInputStream(socket.getInputStream())),pair,Protocol.key(peer.key))
                    require(store.peers().any { it.id==peer.id&&it.trusted });return answer
                } } finally { timeout.cancel(false);sockets.remove(connection) }
            } catch(error:Throwable) { failure=error }
        }
        throw failure ?: java.io.IOException("no LAN route")
    }
    fun wake() { store.retry(); diagnostics.network("手動再探索","要求"); scheduleTick() }
    fun diagnosticReport(): String = diagnostics.report(
        status=status,
        peers=store.peers(),
        networks=networkSummary(),
        vpn=vpnSummary(),
        multicastHeld=multicast?.isHeld == true,
        discoveryReady=discoveryReady,
        messagesReady=messagesReady
    )
    private fun networkSummary(): List<String> = networks().map { network ->
        val lp = cm.getLinkProperties(network)
        val addresses = lp?.linkAddresses?.filter { it.address is Inet4Address }
            ?.joinToString(",") { it.address.hostAddress.orEmpty()+"/"+it.prefixLength }.orEmpty()
        network.toString()+if(addresses.isEmpty()) "" else " "+addresses
    }
    private fun vpnSummary(): String {
        val vpns = cm.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if(vpns.isEmpty()) return "なし"
        val activeIsVpn = cm.activeNetwork?.let {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        return if(activeIsVpn) "あり（既定経路）" else "あり（非既定）"
    }
    fun close() {
        networkReconcileGate.next();running.set(false); udp?.close(); tcp?.close()
        clock.shutdownNow(); sender.shutdownNow(); workers.shutdownNow(); deadlines.shutdownNow()
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        if(callbackRegistered) runCatching { cm.unregisterNetworkCallback(callback) }
        multicast?.let { if(it.isHeld) it.release() }
    }

    private data class DeliveryAttempt(val letter: Letter,val peer: Peer,
        val networks: List<Network>,val offlineFallback: Boolean)
}

internal data class DeliveryCandidate(val letter: Letter,val peer: Peer,val offlineFallback: Boolean)

internal object LanRetryPolicy {
    /**
     * ONLINE peers can drain their due queue. OFFLINE peers get at most one known-IP probe per
     * tick; Store.attempted() then enforces the persistent 10s..5min exponential backoff.
     */
    fun select(pending: List<Letter>,peers: List<Peer>,now: Long,limit: Int,
        reachable: (String) -> Boolean): List<DeliveryCandidate> {
        val trusted=peers.filter { it.trusted }.associateBy { it.id }
        val offlineSelected=mutableSetOf<String>()
        return pending.asSequence().mapNotNull { letter ->
            val peer=trusted[letter.peer] ?: return@mapNotNull null
            if(!reachable(peer.host)) return@mapNotNull null
            val offline=!peer.online(now)
            if(offline && !offlineSelected.add(peer.id)) return@mapNotNull null
            DeliveryCandidate(letter,peer,offline)
        }.take(limit.coerceAtLeast(0)).toList()
    }
}

internal class NetworkReconcileGate {
    private var generation=0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun isCurrent(value: Long): Boolean = generation == value
    @Synchronized fun runIfCurrent(value: Long,action: () -> Unit): Boolean {
        if(generation != value) return false
        action()
        return true
    }
}


