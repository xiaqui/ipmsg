package com.xiaqui.ipmsg

import java.util.ArrayDeque
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local LAN telemetry. It intentionally records no message body, public key, or secret.
 * The report is designed for screenshots from old Android devices during household LAN diagnosis.
 */
internal class LanDiagnostics(private val clock: () -> Long = System::currentTimeMillis) {
    private val udpTx = AtomicLong()
    private val udpTxUnicast = AtomicLong()
    private val udpRx = AtomicLong()
    private val udpRxSelf = AtomicLong()
    private val udpRxPeer = AtomicLong()
    private val udpRxDropped = AtomicLong()
    private val helloOk = AtomicLong()
    private val helloRejected = AtomicLong()
    private val tcpAttempt = AtomicLong()
    private val tcpConnected = AtomicLong()
    private val tcpFailed = AtomicLong()
    private val tcpIncoming = AtomicLong()
    private val deliveryFailed = AtomicLong()
    private val ackReceived = AtomicLong()
    private val ackSent = AtomicLong()
    private val offlineTcpAttempt = AtomicLong()
    private val events = ArrayDeque<String>()

    @Volatile private var lastUdpTx = 0L
    @Volatile private var lastUdpRx = 0L
    @Volatile private var lastHelloOk = 0L
    @Volatile private var lastTcp = 0L
    @Volatile private var lastAck = 0L
    @Volatile private var lastReject = "なし"
    @Volatile private var lastTcpFailure = "なし"
    @Volatile private var pending = 0
    @Volatile private var waitingOffline = 0

    fun udpSent(target: String, knownPeer: Boolean = false) {
        udpTx.incrementAndGet()
        if(knownPeer) udpTxUnicast.incrementAndGet()
        lastUdpTx = clock()
        event(if(knownPeer) "UDPユニキャスト" else "UDPブロードキャスト", target)
    }
    fun udpReceived(host: String, result: UdpReceiveResult, detail: String) {
        udpRx.incrementAndGet()
        when(result) {
            UdpReceiveResult.SELF -> udpRxSelf.incrementAndGet()
            UdpReceiveResult.PEER -> udpRxPeer.incrementAndGet()
            UdpReceiveResult.DROPPED -> udpRxDropped.incrementAndGet()
        }
        lastUdpRx = clock()
        event("UDP受信",host+" 結果="+result.label+if(detail.isEmpty()) "" else " "+detail.take(80))
    }
    fun helloAccepted(peer: String, host: String) {
        helloOk.incrementAndGet(); lastHelloOk = clock(); event("署名OK", peer.take(8)+" @"+host)
    }
    fun helloRejected(host: String, reason: String) {
        helloRejected.incrementAndGet(); lastReject = reason+" @"+host; event("探索拒否", lastReject)
    }
    fun tcpAttempt(peer: String, host: String, offlineFallback: Boolean = false) {
        tcpAttempt.incrementAndGet(); lastTcp = clock(); event("TCP接続", peer.take(8)+" → "+host)
        if(offlineFallback) offlineTcpAttempt.incrementAndGet()
    }
    fun tcpConnected(peer: String) {
        tcpConnected.incrementAndGet(); lastTcp = clock(); event("TCP成功", peer.take(8))
    }
    fun tcpFailed(peer: String, reason: String) {
        tcpFailed.incrementAndGet(); lastTcp = clock()
        lastTcpFailure = reason.take(80); event("TCP接続失敗", peer.take(8)+" "+lastTcpFailure)
    }
    fun tcpIncoming(peer: String) {
        tcpIncoming.incrementAndGet(); lastTcp = clock(); event("TCP受信",peer.take(8))
    }
    fun deliveryFailed(peer: String, stage: String, reason: String) {
        deliveryFailed.incrementAndGet(); lastTcp = clock(); lastTcpFailure=(stage+" "+reason).take(80)
        event("配送失敗",peer.take(8)+" "+lastTcpFailure)
    }
    fun ackReceived(peer: String) {
        ackReceived.incrementAndGet(); lastAck = clock(); event("ACK受信", peer.take(8))
    }
    fun ackSent(peer: String) {
        ackSent.incrementAndGet(); lastAck = clock(); event("ACK送信", peer.take(8))
    }
    fun network(kind: String, detail: String) = event("回線"+kind, detail.take(100))
    fun queue(total: Int, offline: Int) { pending = total; waitingOffline = offline }

    @Synchronized private fun event(kind: String, detail: String) {
        events.addFirst(formatTime(clock())+" "+kind+" "+detail)
        while(events.size > 24) events.removeLast()
    }

    @Synchronized fun report(
        status: String,
        peers: List<Peer>,
        networks: List<String>,
        vpn: String,
        multicastHeld: Boolean,
        discoveryReady: Boolean,
        messagesReady: Boolean,
        now: Long = clock()
    ): String {
        val peerLines = if(peers.isEmpty()) "  なし" else peers.joinToString("\n") {
            val age = if(it.seen <= 0) "未受信" else age(now,it.seen)
            "  "+(if(it.trusted) "承認" else "未承認")+" "+safePeerName(it.name)+" "+it.id.take(8)+
                " "+it.host+" 最終発見:"+timestamp(it.seen)+"（"+age+"） "+
                (if(it.online(now)) "ONLINE" else "OFFLINE")
        }
        val eventLines = if(events.isEmpty()) "  なし" else events.joinToString("\n") { "  "+it }
        return buildString {
            appendLine("状態: "+status)
            appendLine("受信ポート: UDP="+ready(discoveryReady)+" / TCP="+ready(messagesReady))
            appendLine("MulticastLock: "+if(multicastHeld) "保持" else "未保持")
            appendLine("VPN: "+vpn)
            appendLine("LAN: "+if(networks.isEmpty()) "なし" else networks.joinToString(" | "))
            appendLine("UDP: 送信 "+udpTx.get()+"（最終 "+age(now,lastUdpTx)+"） / 受信 "+udpRx.get()+"（最終 "+age(now,lastUdpRx)+"）")
            appendLine("UDP内訳: 既知IPユニキャスト送信 "+udpTxUnicast.get()+" / 自己受信 "+udpRxSelf.get()+
                " / 他端末受信 "+udpRxPeer.get()+" / 破棄 "+udpRxDropped.get())
            appendLine("探索検証: 成功 "+helloOk.get()+"（最終 "+age(now,lastHelloOk)+"） / 拒否 "+helloRejected.get()+"（最終 "+lastReject+"）")
            appendLine("TCP送信: 試行 "+tcpAttempt.get()+"（うちOFFLINE既知IP "+offlineTcpAttempt.get()+
                "） / 接続 "+tcpConnected.get()+" / 接続失敗 "+tcpFailed.get())
            appendLine("TCP受信: "+tcpIncoming.get())
            appendLine("ACK: 受信 "+ackReceived.get()+" / 送信 "+ackSent.get()+" / ACK・送信段階失敗 "+deliveryFailed.get()+
                "（最終 "+lastTcpFailure+"、ACK最終 "+age(now,lastAck)+"）")
            appendLine("保留: "+pending+"件（オンライン待ち "+waitingOffline+"件）")
            appendLine("端末別:")
            appendLine(peerLines)
            appendLine("直近イベント:")
            append(eventLines)
        }
    }

    private fun timestamp(value: Long): String =
        if(value <= 0) "なし" else SimpleDateFormat("MM-dd HH:mm:ss",Locale.JAPAN).format(Date(value))
    companion object {
        fun safePeerName(value: String): String = buildString {
            var offset = 0
            var count = 0
            while(offset < value.length && count < 64) {
                val codePoint = Character.codePointAt(value,offset)
                val type = Character.getType(codePoint)
                when(codePoint) {
                    0x0a -> append("\\n")
                    0x0d -> append("\\r")
                    0x09 -> append("\\t")
                    else -> if(Character.isISOControl(codePoint) ||
                        type == Character.FORMAT.toInt() ||
                        type == Character.LINE_SEPARATOR.toInt() ||
                        type == Character.PARAGRAPH_SEPARATOR.toInt())
                        append("\\u{"+codePoint.toString(16).padStart(4,'0')+"}")
                    else append(String(Character.toChars(codePoint)))
                }
                offset += Character.charCount(codePoint)
                count++
            }
        }
    }
    private fun ready(value: Boolean) = if(value) "待受中" else "停止/再試行"
    private fun age(now: Long, then: Long): String =
        if(then <= 0) "なし" else String.format(Locale.US,"%.1f秒前",((now-then).coerceAtLeast(0))/1000.0)
    private fun formatTime(value: Long): String =
        SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(Date(value))
}

internal enum class UdpReceiveResult(val label: String) {
    SELF("自己HELLO"),
    PEER("他端末HELLO"),
    DROPPED("破棄")
}

internal object LanNetworkPolicy {
    fun clearPeersAfterLost(remainingLanNetworks: Int): Boolean = remainingLanNetworks == 0
    fun prefixMembershipLost(previous: Set<String>, current: Set<String>): Boolean =
        previous.isNotEmpty() && previous.intersect(current).isEmpty()
    fun hostOnPrefixes(host: String,prefixes: Set<String>): Boolean {
        val address=parseIpv4(host) ?: return false
        return prefixes.any { value ->
            val slash=value.lastIndexOf('/')
            if(slash <= 0) return@any false
            val network=parseIpv4(value.substring(0,slash)) ?: return@any false
            val length=value.substring(slash+1).toIntOrNull() ?: return@any false
            Protocol.onPrefix(address,network,length)
        }
    }
    fun parseIpv4(host: String): java.net.InetAddress? {
        val parts=host.split('.')
        if(parts.size != 4) return null
        val bytes=ByteArray(4)
        parts.forEachIndexed { index,part ->
            if(part.isEmpty() || (part.length > 1 && part[0] == '0')) return null
            val value=part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index]=value.toByte()
        }
        return java.net.InetAddress.getByAddress(bytes)
    }
}

