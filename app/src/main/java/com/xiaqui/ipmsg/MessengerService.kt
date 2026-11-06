package com.xiaqui.ipmsg

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.Executors

class MessengerService : Service() {
    companion object {
        @Volatile var active = false
        @Volatile var engine: LanEngine? = null
        @Volatile var problem = ""
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
            runCatching { Store(context).use { it.offline() } }
            context.stopService(Intent(context,MessengerService::class.java))
            MessengerNotifications.showPaused(context)
        }
    }
    private val init = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var db: Store? = null
    override fun onCreate() {
        super.onCreate()
        MessengerNotifications.ensureChannels(this)
        val notification = MessengerNotifications.active(this)
        if(Build.VERSION.SDK_INT >= 29)
            startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1,notification)
        if(!ReceiverState.enabled(this)) { stopSelf();return }
        active = true; problem = ""
        init.execute {
            runCatching {
                val store = Store(this)
                val lan = LanEngine(this,store,Identity.load(),::notifyLetter,::notifyFileOffer)
                synchronized(this) {
                    if(destroyed) { store.close(); return@execute }
                    db = store; engine = lan; lan.start()
                }
            }.onFailure {
                problem = "受信を開始できませんでした。アプリから受信を再開してください"
                android.os.Handler(mainLooper).post { stopSelf() }
            }
        }
    }
    private fun notifyLetter(letter: Letter) {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = MessengerNotifications.open(this,true)
        try {
            NotificationManagerCompat.from(this).notify(2,NotificationCompat.Builder(this,"letters")
                .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("IP Messenger：手紙が届きました")
                .setContentText("受信箱を開いて確認").setContentIntent(open).setAutoCancel(true).build())
        } catch(_: SecurityException) { }
    }
    private fun notifyFileOffer(name:String,transferId:String) {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open=PendingIntent.getActivity(this,3,
            Intent(this,MainActivity::class.java).putExtra("files",true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { NotificationManagerCompat.from(this).notify(3,NotificationCompat.Builder(this,"letters")
            .setSmallIcon(R.drawable.ic_notification_envelope).setContentTitle("$name からファイル")
            .setContentText("開いて保存先を選んでください").setContentIntent(open).setAutoCancel(true).build())
        } catch(_:SecurityException) { }
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        synchronized(this) {
            destroyed = true; engine?.close(); engine = null; active = false
            // Listener threads may still unwind; SQLite helper remains valid until process exit.
        }
        init.shutdownNow()
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
            .setContentText("LAN通信停止中・タップで再開")
            .setContentIntent(resume).addAction(R.drawable.ic_notification_envelope,"再開",resume)
            .setOngoing(true).build()
        try { NotificationManagerCompat.from(context).notify(PAUSED_ID,notification) }
        catch(_:SecurityException) { }
    }
    fun cancelPaused(context:Context)=NotificationManagerCompat.from(context).cancel(PAUSED_ID)
}

