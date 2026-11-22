package com.xiaqui.ipmsg

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class MessengerService : Service() {
    companion object {
        @Volatile var active = false
        val engine: LanEngine? get() = LanRuntime.engine
        val problem: String get() = LanRuntime.problem
        fun start(context: Context) {
            val intent = Intent(context,MessengerService::class.java)
            if(Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun resume(context: Context) {
            check(ReceiverState.resume(context)) { "receiver state was not saved" }
            try {
                start(context)
                MessengerNotifications.cancelPaused(context)
            } catch(error:RuntimeException) {
                ReceiverState.pause(context)
                MessengerNotifications.showPaused(context)
                throw error
            }
        }
        fun pause(context: Context) {
            ReceiverState.pause(context)
            LanRuntime.pauseBackground(context)
            context.stopService(Intent(context,MessengerService::class.java))
            MessengerNotifications.showPaused(context)
        }
    }
    override fun onCreate() {
        super.onCreate()
        MessengerNotifications.ensureChannels(this)
        val notification = MessengerNotifications.active(this)
        if(Build.VERSION.SDK_INT >= 29)
            startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1,notification)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val enabled = ReceiverState.enabled(this)
        active = enabled
        LanRuntime.background(this,this,enabled)
        if(!enabled) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        LanRuntime.background(this,this,false)
        active = false
        super.onDestroy()
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if(ReceiverState.enabled(context)) {
            runCatching { MessengerService.start(context) }
        } else if(ReceiverState.paused(context)) MessengerNotifications.showPaused(context)
    }
}
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==MessengerNotifications.ACTION_PAUSE) MessengerService.pause(context)
    }
}
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==MessengerNotifications.ACTION_RESUME)
            runCatching { MessengerService.resume(context) }
                .onFailure { MessengerNotifications.showPaused(context) }
    }
}

object ReceiverState {
    private const val ENABLED="enabled"
    private const val PAUSED="paused_by_user"
    private fun prefs(context:Context)=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    fun enabled(context:Context)=prefs(context).getBoolean(ENABLED,false)
    fun paused(context:Context)=prefs(context).getBoolean(PAUSED,false)
    fun resume(context:Context)=prefs(context).edit().putBoolean(ENABLED,true).putBoolean(PAUSED,false).commit()
    fun pause(context:Context)=prefs(context).edit().putBoolean(ENABLED,false).putBoolean(PAUSED,true).commit()
}

object MessengerNotifications {
    const val ACTION_PAUSE="com.xiaqui.ipmsg.action.PAUSE_RECEIVER"
    const val ACTION_RESUME="com.xiaqui.ipmsg.action.RESUME_RECEIVER"
    private const val PAUSED_ID=4
    fun ensureChannels(context:Context) {
        if(Build.VERSION.SDK_INT < 26) return
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("receiver","常駐受信",NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("letters","手紙の到着",NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun open(context: Context,inbox: Boolean): PendingIntent = PendingIntent.getActivity(
        context,if(inbox) 1 else 0,
        Intent(context,MainActivity::class.java).putExtra("inbox",inbox),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun active(context:Context):Notification {
        val pause=PendingIntent.getBroadcast(context,4,Intent(context,PauseReceiver::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context,"receiver")
            .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("IP Messenger：受信中")
            .setContentText("LAN受信中・タップでアプリを開く")
            .setContentIntent(open(context,false)).addAction(R.drawable.ic_notification_envelope,"一時停止",pause)
            .setOngoing(true).build()
    }
    fun showPaused(context:Context) {
        ensureChannels(context)
        val resume=PendingIntent.getBroadcast(context,5,
            Intent(context,ResumeReceiver::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,"receiver")
            .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("IP Messenger：一時停止中")
            .setContentText("常駐受信は停止中・アプリ使用中は通信します")
            .setContentIntent(resume).addAction(R.drawable.ic_notification_envelope,"再開",resume)
            .setOngoing(true).build()
        try { NotificationManagerCompat.from(context).notify(PAUSED_ID,notification) }
        catch(_:SecurityException) { }
    }
    fun notifyLetter(context:Context) {
        ensureChannels(context)
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(2,NotificationCompat.Builder(context,"letters")
                .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("IP Messenger：手紙が届きました")
                .setContentText("受信箱を開いて確認").setContentIntent(open(context,true)).setAutoCancel(true).build())
        } catch(_: SecurityException) { }
    }
    fun notifyFileOffer(context:Context,name:String) {
        ensureChannels(context)
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open=PendingIntent.getActivity(context,3,
            Intent(context,MainActivity::class.java).putExtra("files",true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { NotificationManagerCompat.from(context).notify(3,NotificationCompat.Builder(context,"letters")
            .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("$name からファイル")
            .setContentText("開いて保存先を選んでください").setContentIntent(open).setAutoCancel(true).build())
        } catch(_:SecurityException) { }
    }
    fun cancelPaused(context:Context)=NotificationManagerCompat.from(context).cancel(PAUSED_ID)
}

