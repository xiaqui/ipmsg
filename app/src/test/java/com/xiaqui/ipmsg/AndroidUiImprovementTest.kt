package com.xiaqui.ipmsg

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class AndroidUiImprovementTest {
    private lateinit var context:Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("ipmsg.db")
    }

    @Test fun explicitPausePersistsUntilExplicitResume() {
        assertFalse(ReceiverState.enabled(context));assertFalse(ReceiverState.paused(context))
        assertTrue(ReceiverState.resume(context));assertTrue(ReceiverState.enabled(context));assertFalse(ReceiverState.paused(context))
        assertTrue(ReceiverState.pause(context));assertFalse(ReceiverState.enabled(context));assertTrue(ReceiverState.paused(context))
        val sameProcessContext=RuntimeEnvironment.getApplication() as Context
        assertFalse(ReceiverState.enabled(sameProcessContext));assertTrue(ReceiverState.paused(sameProcessContext))
        assertTrue(ReceiverState.resume(context));assertTrue(ReceiverState.enabled(context));assertFalse(ReceiverState.paused(context))
    }

    @Test fun receiverNotificationExposesExplicitImmutablePauseAction() {
        val notification=MessengerNotifications.active(context)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("IP Messenger：受信中",notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("LAN受信中・タップでアプリを開く",notification.extras.getString(Notification.EXTRA_TEXT))
        val action=notification.actions.single();assertEquals("一時停止",action.title.toString())
        val saved=org.robolectric.Shadows.shadowOf(action.actionIntent).savedIntent
        assertEquals(MessengerNotifications.ACTION_PAUSE,saved.action)
        assertEquals(PauseReceiver::class.java.name,saved.component?.className)

        MessengerNotifications.showPaused(context)
        val paused=Shadows.shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getNotification(4)
        assertEquals("IP Messenger：一時停止中",paused.extras.getString(Notification.EXTRA_TITLE))
        val resume=paused.actions.single();assertEquals("再開",resume.title.toString())
        val resumeIntent=Shadows.shadowOf(resume.actionIntent).savedIntent
        assertEquals(MessengerNotifications.ACTION_RESUME,resumeIntent.action)
        assertEquals(ResumeReceiver::class.java.name,resumeIntent.component?.className)
    }

    @Test fun bootPreservesManualPauseAndPendingDeliveryData() {
        ReceiverState.pause(context)
        BootReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(ReceiverState.enabled(context));assertTrue(ReceiverState.paused(context))

        val store=Store(context)
        try {
            val peer=Peer("peer","相手","key",true,"192.0.2.1",1,true)
            store.writableDatabase.execSQL("INSERT INTO peers VALUES(?,?,?,?,?,?,?)",
                arrayOf(peer.id,peer.name,peer.key,1,peer.host,peer.seen,1))
            store.enqueue(listOf(peer.id),"self","自分","保留本文",1)
            store.createOutgoingTransfer("transfer",peer,listOf(FileMeta(
                "00000000-0000-4000-8000-000000000001","photo.jpg","image/jpeg",0,FileContract.EMPTY_SHA)))
            MessengerService.pause(context)
            assertEquals("PENDING",store.letters(false).single().state)
            assertEquals(0,store.letters(false).single().attempts)
            assertEquals("QUEUED",store.transfer("transfer",peer.id,false).state)
        } finally { store.close() }
    }

    @Test fun historyStatusChangesFromPendingToAuthenticatedArrival() {
        val pending=Letter("id","peer","端末","本文",false,1,"PENDING",2,0)
        assertEquals("保留／再送待ち（2回）",HistoryAdapter.status(pending,false))
        assertEquals("到着確認済み",HistoryAdapter.status(pending.copy(state="DELIVERED"),false))
        assertEquals("到着",HistoryAdapter.status(pending.copy(incoming=true),true))
        val adapter=HistoryAdapter(context,false)
        assertTrue(adapter.replace(listOf(pending)));assertFalse(adapter.replace(listOf(pending)))
        assertTrue(adapter.replace(listOf(pending.copy(state="DELIVERED"))))
    }

    @Test fun mainScreenUsesSendReceiveFileLabelAndEnvelopeIcon() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun buttons(view:View):List<Button> = when(view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        val labels=buttons(activity.window.decorView).map { it.text.toString() }
        assertTrue("送受信ファイル" in labels);assertFalse("ファイル転送" in labels)
        assertNotEquals(0,context.applicationInfo.icon)
        assertNotNull(context.resources.getDrawable(R.drawable.ic_notification_envelope,null))
        activity.finish()
    }

    @Test fun receivedImageAndApkUseContentGrantClipDataAndReliableMime() {
        val image=Uri.parse("content://documents/tree/root/document/photo")
        val view=ReceivedFileOpener.viewIntent(context.contentResolver,image,"photo.JPG","application/octet-stream")
        assertEquals(Intent.ACTION_VIEW,view.action);assertEquals("image/jpeg",view.type)
        assertEquals(image,view.data);assertEquals(image,view.clipData!!.getItemAt(0).uri)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val chooser=ReceivedFileOpener.chooser(view,"photo.JPG")
        assertEquals(image,chooser.clipData!!.getItemAt(0).uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val apk=ReceivedFileOpener.viewIntent(context.contentResolver,
            Uri.parse("content://documents/tree/root/document/app"),"update.APK","application/octet-stream")
        assertEquals(ReceivedFileOpener.APK_MIME,apk.type)
        assertTrue(ReceivedFileOpener.needsInstallerPermission(apk.type!!,26,false))
        assertFalse(ReceivedFileOpener.needsInstallerPermission(apk.type!!,25,false))
        assertFalse(ReceivedFileOpener.needsInstallerPermission(apk.type!!,26,true))
        assertEquals("image/jpeg",ReceivedFileOpener.mime(context.contentResolver,image,"photo.jpg","text/plain"))
        assertThrows(IllegalArgumentException::class.java) {
            ReceivedFileOpener.viewIntent(context.contentResolver,Uri.parse("file:///tmp/app.apk"),"app.apk",ReceivedFileOpener.APK_MIME)
        }
    }
}
