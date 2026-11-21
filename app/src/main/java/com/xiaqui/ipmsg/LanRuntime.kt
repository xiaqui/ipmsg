package com.xiaqui.ipmsg

import android.content.Context
import java.util.concurrent.Executors

/** Process-wide ownership: an Activity and the foreground service never bind separate ports. */
internal object LanRuntime {
    @Volatile private var controller: LanSessionController? = null
    @Synchronized private fun controller(context: Context): LanSessionController =
        controller ?: run {
            val app = context.applicationContext
            LanSessionController {
                val store = Store(app)
                val engine = try {
                    store.offline()
                    LanEngine(app,store,Identity.load(),
                        { MessengerNotifications.notifyLetter(app) },
                        { name,_ -> MessengerNotifications.notifyFileOffer(app,name) })
                } catch(error: Exception) { store.close(); throw error }
                object : LanSession {
                    override val engine = engine
                    override fun start() = engine.start()
                    override fun stop() = engine.close()
                    override fun awaitStopped() {
                        engine.awaitStopped()
                        store.offline()
                        store.close()
                    }
                }
            }.also { controller = it }
        }
    fun foreground(context: Context,owner: Any,shown: Boolean) = controller(context).foreground(owner,shown)
    fun background(context: Context,owner: Any,enabled: Boolean) = controller(context).background(owner,enabled)
    fun pauseBackground(context: Context) = controller(context).pauseBackground()
    val engine: LanEngine? get() = controller?.engine
    val problem: String get() = controller?.problem.orEmpty()
    fun wake() = controller?.withSession { it.engine?.wake() }
    fun diagnosticReport(): String? = controller?.withSession { it.engine?.diagnosticReport() }
}

internal interface LanSession {
    val engine: LanEngine? get() = null
    fun start()
    /** Stops network admission immediately; draining local writes happens off the UI thread. */
    fun stop()
    fun awaitStopped()
}

/** All starts and drains are serialized. Owners are tokens, so an old Activity cannot stop a new one. */
internal class LanSessionController(private val create: () -> LanSession) {
    private val foreground = mutableSetOf<Any>()
    private val background = mutableSetOf<Any>()
    private val worker = Executors.newSingleThreadExecutor()
    private var session: LanSession? = null
    @Volatile var problem = ""
        private set
    private fun wanted() = foreground.isNotEmpty() || background.isNotEmpty()
    @get:Synchronized val engine: LanEngine? get() = if(wanted() && !stopping) session?.engine else null
    @Synchronized fun <T> withSession(action: (LanSession) -> T): T? =
        session?.takeIf { wanted() && !stopping }?.let(action)
    @Synchronized fun foreground(owner: Any,shown: Boolean) {
        if(shown) foreground.add(owner) else foreground.remove(owner)
        reconcileLater()
    }
    @Synchronized fun background(owner: Any,enabled: Boolean) {
        if(enabled) background.add(owner) else background.remove(owner)
        reconcileLater()
    }
    @Synchronized fun pauseBackground() { background.clear(); reconcileLater() }
    private fun reconcileLater() {
        // Close sockets before returning from the final owner's release. A rapid reacquire
        // must still wait for this old session's threads/SAF finalization before starting.
        if(!wanted()) session?.stopOnce()
        worker.execute { reconcile() }
    }
    private fun reconcile() {
        var retiring: LanSession? = null
        synchronized(this) {
            // A stopped session is detached even if an owner returned before this worker ran.
            if(session != null && (!wanted() || stopping)) {
                retiring = session; session = null; stopping = false
            }
        }
        retiring?.awaitStopped()
        synchronized(this) {
            if(!wanted() || session != null) return
        }
        var candidate: LanSession? = null
        try {
            val created = create()
            candidate = created
            synchronized(this) {
                if(wanted()) {
                    created.start()
                    session = created
                    problem = ""
                    return
                }
            }
        } catch(_: Exception) {
            problem = "通信を開始できませんでした。画面を開き直すか常駐受信を再開してください"
        }
        candidate?.let { it.stop(); it.awaitStopped() }
    }
    private var stopping = false
    // Used by the final-owner release as well as failed initialization cleanup.
    private fun LanSession.stopOnce() { stop(); stopping = true }
    internal fun awaitIdle() { worker.submit {}.get(30,java.util.concurrent.TimeUnit.SECONDS) }
    internal fun shutdown() {
        if(worker.isShutdown) return
        synchronized(this) { foreground.clear(); background.clear(); reconcileLater() }
        awaitIdle(); worker.shutdown()
    }
}
