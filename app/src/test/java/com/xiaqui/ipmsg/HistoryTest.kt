package com.xiaqui.ipmsg

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowAlertDialog
import android.app.AlertDialog
import android.content.ClipboardManager
import android.widget.Button
import android.view.ViewGroup
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class HistoryTest {
    @Test fun pendingDetailHasOneCloseAndCopiesOnlyBody() {
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        val body="本文\nUnicode🍎"
        activity.showLetter(Letter("id","peer","端末",body,false,1,"PENDING",0,0))
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("閉じる",dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility==android.view.View.VISIBLE)
        fun buttons(view: android.view.View): List<Button> = when(view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        val values=buttons(dialog.window!!.decorView)
        assertEquals(1,values.count { it.text=="閉じる" })
        values.single { it.text=="本文をコピー" }.performClick()
        val clip=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(body,clip.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1,values.count { it.text=="削除" })
        dialog.dismiss()
    }
    @Test fun deletedPendingCannotSendAndDeletedIncomingDoesNotRevive() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("ipmsg.db")
        val store=Store(context)
        try {
            val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val id=Protocol.id(pair.public)
            store.discover(Protocol.readHello(Protocol.hello("peer",pair,100000),100000),"192.168.1.2",100000)
            store.trust(id,true)
            store.enqueue(listOf(id),"self","Windows","本文\n🍎",100000)
            val pending=store.pending(100000).single()
            store.deleteLetter(pending)
            var wrote=false
            assertFalse(store.writeIfPending(pending) { wrote=true });assertFalse(wrote)
            store.delivered(pending.id,id);assertTrue(store.letters().isEmpty())
            val mid="00000000-0000-0000-0000-000000000001"
            assertTrue(store.receive(mid,id,"peer","本文\n🍎"))
            store.deleteLetter(store.letters(true).single())
            assertFalse(store.receive(mid,id,"peer","本文\n🍎"))
            assertThrows(IllegalArgumentException::class.java) { store.receive(mid,id,"peer","changed") }
            assertTrue(store.letters().isEmpty())
        } finally { store.close() }
    }
}
