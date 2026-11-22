package com.xiaqui.ipmsg

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

/** Started (visible) Activities own communication, including a paused split-screen pane. */
internal class VisibleLanOwner(private val context: Context) {
    private var visible = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if(intent.action == Intent.ACTION_SCREEN_OFF) LanRuntime.foreground(context,this@VisibleLanOwner,false)
            else refresh()
        }
    }
    fun start() {
        if(visible) return
        visible = true
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT) }
        if(Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(screen,filter)
        refresh()
    }
    fun refresh() {
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        LanRuntime.foreground(context,this,visible && interactive && unlocked)
    }
    fun stop() {
        if(visible) { visible = false;context.unregisterReceiver(screen) }
        LanRuntime.foreground(context,this,false)
    }
}
