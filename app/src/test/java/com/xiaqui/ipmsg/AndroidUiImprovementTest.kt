package com.xiaqui.ipmsg

import android.app.Notification
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class AndroidUiImprovementTest {
    private lateinit var context:Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().clear().commit()
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
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
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
        val apk=ReceivedFileOpener.viewIntent(context.contentResolver,
            Uri.parse("content://documents/tree/root/document/app"),"update.APK","application/octet-stream")
        assertEquals(ReceivedFileOpener.APK_MIME,apk.type)
        assertThrows(IllegalArgumentException::class.java) {
            ReceivedFileOpener.viewIntent(context.contentResolver,Uri.parse("file:///tmp/app.apk"),"app.apk",ReceivedFileOpener.APK_MIME)
        }
    }
}
