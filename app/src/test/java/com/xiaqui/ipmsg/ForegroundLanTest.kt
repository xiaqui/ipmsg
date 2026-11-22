package com.xiaqui.ipmsg

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,26,28,29,34])
class ForegroundLanTest {
    private lateinit var context:Context
    private lateinit var controller:LanSessionController
    private val starts=AtomicInteger()
    private val stops=AtomicInteger()
    @Before fun setup() {
        java.security.Security.insertProviderAt(TestIdentityProvider(),1)
        context=RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("ipmsg.db")
        controller=LanSessionController { object : LanSession {
            override fun start() { starts.incrementAndGet() }
            override fun stop() { stops.incrementAndGet() }
            override fun awaitStopped() { }
        } }
        LanRuntime::class.java.getDeclaredField("controller").apply { isAccessible=true }.set(LanRuntime,controller)
        MessengerService.active=false
    }
    @After fun cleanup() {
        java.security.Security.removeProvider("IpmsgTestIdentity")
        controller.shutdown()
        LanRuntime::class.java.getDeclaredField("controller").apply { isAccessible=true }.set(LanRuntime,null)
        MessengerService.active=false
    }
    @Test fun offUsesOnlyVisibleOwnershipAndBootDoesNotEnableIt() {
        val activity=Any()
        LanRuntime.foreground(context,activity,true);controller.awaitIdle()
        assertEquals(1,starts.get());assertFalse(ReceiverState.enabled(context))
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
        LanRuntime.foreground(context,activity,false);controller.awaitIdle()
        assertTrue(stops.get()>0)
        ReceiverState.pause(context)
        BootReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(ReceiverState.enabled(context));assertEquals(1,starts.get())
        assertNull(Shadows.shadowOf(context as android.app.Application).nextStartedService)
        LanRuntime.foreground(context,activity,true);controller.awaitIdle()
        assertEquals(2,starts.get());assertTrue(ReceiverState.paused(context))
    }
    @Test fun actualActivityKeepsPausedVisiblePaneButStopsOnHomeAndScreenOff() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            controller.awaitIdle();assertEquals(1,starts.get());assertFalse(ReceiverState.enabled(context))
            activity.pause();controller.awaitIdle()
            assertEquals(0,stops.get()) // Paused split-screen pane is still displayed.
            activity.stop();controller.awaitIdle();assertTrue(stops.get()>0)
            activity.start().resume();controller.awaitIdle();assertEquals(2,starts.get())
            val before=stops.get()
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.awaitIdle();assertTrue(stops.get()>before)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            controller.awaitIdle();assertEquals(3,starts.get())
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun serviceAndNotificationPausePreserveVisibleSession() {
        val activity=Any();LanRuntime.foreground(context,activity,true);controller.awaitIdle()
        ReceiverState.resume(context)
        val service=Robolectric.buildService(MessengerService::class.java).create().startCommand(0,1)
        try {
            controller.awaitIdle();assertEquals(1,starts.get());assertTrue(MessengerService.active)
            LanRuntime.foreground(context,activity,false);controller.awaitIdle()
            assertEquals(0,stops.get())
            LanRuntime.foreground(context,activity,true)
            PauseReceiver().onReceive(context,Intent(MessengerNotifications.ACTION_PAUSE))
            service.destroy();controller.awaitIdle()
            assertEquals(0,stops.get());assertFalse(ReceiverState.enabled(context))
            LanRuntime.foreground(context,activity,false);controller.awaitIdle()
            assertTrue(stops.get()>0)
        } finally { if(MessengerService.active) service.destroy() }
    }
    @Test fun notificationPauseRefreshesAnAlreadyVisibleSettingsSwitch() {
        ReceiverState.resume(context)
        val service=Robolectric.buildService(MessengerService::class.java).create().startCommand(0,1)
        val activity=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        fun views(view: android.view.View): List<android.view.View> = listOf(view)+
            if(view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        try {
            controller.awaitIdle()
            views(activity.get().window.decorView).filterIsInstance<android.widget.Button>()
                .first { it.text.toString()=="設定・端末承認" }.performClick()
            val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            val toggle=views(dialog.window!!.decorView).filterIsInstance<android.widget.Switch>().single()
            assertTrue(toggle.isChecked)
            PauseReceiver().onReceive(context,Intent(MessengerNotifications.ACTION_PAUSE))
            service.destroy()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(2,java.util.concurrent.TimeUnit.SECONDS)
            controller.awaitIdle()
            assertFalse(toggle.isChecked);assertFalse(ReceiverState.enabled(context))
            assertEquals(1,starts.get());assertEquals(0,stops.get())
            dialog.dismiss()
        } finally { activity.pause().stop().destroy();if(MessengerService.active) service.destroy() }
    }
    @Test fun actualEngineReleasesPortsAndInvalidatesOnlineOnFinalOwnerRelease() {
        controller.shutdown()
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store=Store(context)
        val id=Protocol.id(pair.public)
        store.discover(Protocol.readHello(Protocol.hello("test",pair,System.currentTimeMillis()),System.currentTimeMillis()),
            "192.0.2.1",System.currentTimeMillis())
        controller=LanSessionController { object : LanSession {
            override val engine=LanEngine(context,store,pair, {})
            override fun start()=engine.start()
            override fun stop()=engine.close()
            override fun awaitStopped() { engine.awaitStopped();store.offline() }
        } }
        try {
            val owner=Any();controller.foreground(owner,true);controller.awaitIdle()
            assertNotNull(controller.engine)
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(controller.engine!!.diagnosticReport().let { !it.contains("受信ポート: UDP=待受中 / TCP=待受中") } && System.nanoTime()<deadline) Thread.yield()
            val report=controller.engine!!.diagnosticReport()
            assertTrue(report,report.contains("受信ポート: UDP=待受中 / TCP=待受中"))
            controller.foreground(owner,false);controller.awaitIdle()
            assertNull(controller.engine);assertEquals(0L,store.peers().first { it.id==id }.seen)
            ServerSocket(Protocol.PORT).use { assertTrue(it.isBound) }
            DatagramSocket(Protocol.PORT).use { assertTrue(it.isBound) }
            controller.foreground(owner,true);controller.awaitIdle()
            assertNotNull(controller.engine)
        } finally { controller.shutdown();store.close() }
    }
    @Test fun partialIncomingFileAndPendingTextSurviveScreenSessionRestart() {
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val peerId=Protocol.id(pair.public);val id=UUID.randomUUID().toString();val fileId=UUID.randomUUID().toString()
        val prefix="first chunk".toByteArray();val bytes=prefix+" still pending".toByteArray()
        val temp=java.io.File(context.filesDir,"$id.part").apply { writeBytes(ByteArray(0)) }
        Store(context).use { store ->
            store.discover(Protocol.readHello(Protocol.hello("peer",pair,100000),100000),"192.0.2.1",100000)
            store.trust(peerId,true);val peer=store.peers().single()
            store.enqueue(listOf(peerId),"self","self","pending text")
            val manager=FileTransferManager(context,store, {}, {_,_->})
            manager.handle(peer,JSONObject().put("type","FILE_OFFER").put("transferId",id).put("files",
                JSONArray().put(JSONObject().put("fileId",fileId).put("name","sample.txt").put("mime","text/plain")
                    .put("size",bytes.size).put("sha256",FileContract.sha(bytes)))))
            store.acceptTransfer(id,peerId,"content://test/tree/root",mapOf(fileId to temp.absolutePath))
            manager.handle(peer,JSONObject().put("type","FILE_CHUNK").put("transferId",id).put("fileId",fileId)
                .put("seq",0).put("offset",0).put("data",Protocol.encode(prefix)).put("sha256",FileContract.sha(prefix)))
            val owner=Any();controller.foreground(owner,true);controller.awaitIdle()
            controller.foreground(owner,false);controller.awaitIdle()
        }
        Store(context).use { store ->
            assertEquals("PENDING",store.letters(false).single().state)
            val peer=store.peers().single()
            val restarted=FileTransferManager(context,store, {}, {_,_->})
            val reply=restarted.handle(peer,JSONObject().put("type","FILE_RESUME").put("transferId",id)
                .put("fileId",fileId).put("offset",0).put("prefixSha256",FileContract.EMPTY_SHA))
            assertEquals(prefix.size.toLong(),reply.getLong("nextOffset"))
            assertEquals(FileContract.sha(prefix),reply.getString("prefixSha256"))
            assertArrayEquals(prefix,temp.readBytes())
        }
    }
}
