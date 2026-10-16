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
    private val udpRx = AtomicLong()
    private val helloOk = AtomicLong()
    private val helloRejected = AtomicLong()
    private val tcpAttempt = AtomicLong()
    private val tcpConnected = AtomicLong()
    private val tcpFailed = AtomicLong()
    private val ackReceived = AtomicLong()
    private val ackSent = AtomicLong()
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

    fun udpSent(target: String) {
        udpTx.incrementAndGet(); lastUdpTx = clock(); event("UDP送信", target)
    }
    fun udpReceived(host: String) {
        udpRx.incrementAndGet(); lastUdpRx = clock(); event("UDP受信", host)
    }
    fun helloAccepted(peer: String, host: String) {
        helloOk.incrementAndGet(); lastHelloOk = clock(); event("署名OK", peer.take(8)+" @"+host)
    }
    fun helloRejected(host: String, reason: String) {
        helloRejected.incrementAndGet(); lastReject = reason+" @"+host; event("探索拒否", lastReject)
    }
    fun tcpAttempt(peer: String, host: String) {
        tcpAttempt.incrementAndGet(); lastTcp = clock(); event("TCP接続", peer.take(8)+" → "+host)
    }
    fun tcpConnected(peer: String) {
        tcpConnected.incrementAndGet(); lastTcp = clock(); event("TCP成功", peer.take(8))
    }
    fun tcpFailed(peer: String, reason: String) {
        tcpFailed.incrementAndGet(); lastTcp = clock()
        lastTcpFailure = reason.take(80); event("TCP失敗", peer.take(8)+" "+lastTcpFailure)
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
            "  "+(if(it.trusted) "承認" else "未承認")+" "+it.name+" "+it.id.take(8)+
                " "+it.host+" 最終発見:"+age+" "+(if(it.online(now)) "ONLINE" else "OFFLINE")
        }
        val eventLines = if(events.isEmpty()) "  なし" else events.joinToString("\n") { "  "+it }
        return buildString {
            appendLine("状態: "+status)
            appendLine("受信ポート: UDP="+ready(discoveryReady)+" / TCP="+ready(messagesReady))
            appendLine("MulticastLock: "+if(multicastHeld) "保持" else "未保持")
            appendLine("VPN: "+vpn)
            appendLine("LAN: "+if(networks.isEmpty()) "なし" else networks.joinToString(" | "))
            appendLine("UDP: 送信 "+udpTx.get()+"（最終 "+age(now,lastUdpTx)+"） / 受信 "+udpRx.get()+"（最終 "+age(now,lastUdpRx)+"）")
            appendLine("探索検証: 成功 "+helloOk.get()+"（最終 "+age(now,lastHelloOk)+"） / 拒否 "+helloRejected.get()+"（最終 "+lastReject+"）")
            appendLine("TCP: 試行 "+tcpAttempt.get()+" / 接続 "+tcpConnected.get()+" / 失敗 "+tcpFailed.get()+"（最終 "+lastTcpFailure+"）")
            appendLine("ACK: 受信 "+ackReceived.get()+" / 送信 "+ackSent.get()+"（最終 "+age(now,lastAck)+"）")
            appendLine("保留: "+pending+"件（オンライン待ち "+waitingOffline+"件）")
            appendLine("端末別:")
            appendLine(peerLines)
            appendLine("直近イベント:")
            append(eventLines)
        }
    }

    private fun ready(value: Boolean) = if(value) "待受中" else "停止/再試行"
    private fun age(now: Long, then: Long): String =
        if(then <= 0) "なし" else String.format(Locale.US,"%.1f秒前",((now-then).coerceAtLeast(0))/1000.0)
    private fun formatTime(value: Long): String =
        SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(Date(value))
}

internal object LanNetworkPolicy {
    fun clearPeersAfterLost(remainingLanNetworks: Int): Boolean = remainingLanNetworks == 0
}
