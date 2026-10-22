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
    }
    private val init = Executors.newSingleThreadExecutor()
    private var destroyed = false
    private var db: Store? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("receiver","常駐受信",NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("letters","手紙の到着",NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = MessengerNotifications.open(this,false)
        val notification = NotificationCompat.Builder(this,"receiver")
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("IP Messenger")
            .setContentText("LAN受信を有効にしています。停止はアプリの設定から")
            .setContentIntent(open).setOngoing(true).build()
        if(Build.VERSION.SDK_INT >= 29)
            startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1,notification)
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
                .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("IP Messenger：手紙が届きました")
                .setContentText("受信箱を開いて確認").setContentIntent(open).setAutoCancel(true).build())
        } catch(_: SecurityException) { }
    }
    private fun notifyFileOffer(name:String,transferId:String) {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open=PendingIntent.getActivity(this,3,
            Intent(this,MainActivity::class.java).putExtra("files",true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { NotificationManagerCompat.from(this).notify(3,NotificationCompat.Builder(this,"letters")
            .setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("$name からファイル")
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
        if(context.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("enabled",false)) {
            runCatching { MessengerService.start(context) }
        }
    }
}

object MessengerNotifications {
    fun open(context: Context,inbox: Boolean): PendingIntent = PendingIntent.getActivity(
        context,if(inbox) 1 else 0,
        Intent(context,MainActivity::class.java).putExtra("inbox",inbox),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

