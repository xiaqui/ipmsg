package com.xiaqui.ipmsg

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.UUID

data class Peer(val id: String, val name: String, val key: String, val trusted: Boolean,
    val host: String, val seen: Long) {
    fun online(now: Long) = seen > 0 && now - seen in 0..35000
}
data class Letter(val id: String, val peer: String, val name: String, val body: String,
    val incoming: Boolean, val created: Long, val state: String, val attempts: Int, val next: Long)

/** All delivery transitions commit synchronously before an ACK is emitted. */
class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "ipmsg.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY,name TEXT NOT NULL,pub TEXT NOT NULL,trusted INTEGER NOT NULL,host TEXT NOT NULL,seen INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE letters(id TEXT NOT NULL,peer TEXT NOT NULL,name TEXT NOT NULL,body TEXT NOT NULL,incoming INTEGER NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id,peer,incoming))")
        db.execSQL("CREATE INDEX pending_due ON letters(incoming,state,next,created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun peers(): List<Peer> = readableDatabase.rawQuery(
        "SELECT id,name,pub,trusted,host,seen FROM peers ORDER BY trusted DESC,name", null).use { c ->
        buildList { while(c.moveToNext()) add(Peer(c.getString(0),c.getString(1),c.getString(2),
            c.getInt(3) == 1,c.getString(4),c.getLong(5))) }
    }
    @Synchronized fun discover(hello: Protocol.Hello, host: String, now: Long) {
        val old = peers().find { it.id == hello.id }
        // Discovery is authenticated but replayable for two minutes. Never grants trust.
        writableDatabase.delete("peers", "trusted=0 AND seen<?", arrayOf((now - 120000).toString()))
        if (old == null && peers().size >= 64) return
        if (old != null && old.key != hello.key) return
        val db = writableDatabase
        // Insert once with default trust=false. Discovery never writes an existing trust flag.
        val initial = ContentValues().apply {
            put("id",hello.id);put("name",hello.name);put("pub",hello.key)
            put("trusted",0);put("host",host);put("seen",now)
        }
        db.insertWithOnConflict("peers",null,initial,SQLiteDatabase.CONFLICT_IGNORE)
        db.update("peers",ContentValues().apply {
            put("name",hello.name);put("host",host);put("seen",now)
        },"id=? AND pub=?",arrayOf(hello.id,hello.key))
    }

    @Synchronized fun trust(id: String, value: Boolean) {
        writableDatabase.update("peers", ContentValues().apply { put("trusted",if(value) 1 else 0) },
            "id=?",arrayOf(id))
    }
    @Synchronized fun offline() { writableDatabase.execSQL("UPDATE peers SET seen=0") }
    @Synchronized fun enqueue(ids: Collection<String>, self: String, selfName: String, body: String,
        now: Long = System.currentTimeMillis()): String {
        require(Protocol.validText(body))
        val targets = ids.toSet()
        require(targets.isNotEmpty())
        val known = peers().filter { it.trusted }.associateBy { it.id }
        require(targets.all { it == self || known.containsKey(it) })
        val id = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            targets.forEach { peer ->
                check(insert(db,id,peer,known[peer]?.name ?: selfName,body,false,now,if(peer == self) "DELIVERED" else "PENDING") != -1L)
                if(peer == self) check(insert(db,id,self,selfName,body,true,now,"RECEIVED") != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    private fun insert(db: SQLiteDatabase,id: String,peer: String,name: String,body: String,
        incoming: Boolean,created: Long,state: String): Long = db.insertWithOnConflict(
        "letters",null,ContentValues().apply {
            put("id",id);put("peer",peer);put("name",name);put("body",body)
            put("incoming",if(incoming) 1 else 0);put("created",created);put("state",state)
        },SQLiteDatabase.CONFLICT_IGNORE)
    @Synchronized fun receive(id: String,peer: String,name: String,body: String,
        now: Long = System.currentTimeMillis()): Boolean {
        require(Protocol.validMessageId(id) && Protocol.validText(body))
        // Exceptions propagate: failed durable storage must never generate an ACK.
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(peers().any { it.id == peer && it.trusted })
            val inserted = insert(db,id,peer,name,body,true,now,"RECEIVED") != -1L
            if (!inserted) {
                db.rawQuery("SELECT body FROM letters WHERE id=? AND peer=? AND incoming=1",
                    arrayOf(id,peer)).use { c -> check(c.moveToFirst() && c.getString(0) == body) }
            }
            db.setTransactionSuccessful()
            return inserted
        } finally { db.endTransaction() }
    }
    @Synchronized fun delivered(id: String,peer: String) {
        writableDatabase.execSQL("UPDATE letters SET state='DELIVERED' WHERE id=? AND peer=? AND incoming=0",
            arrayOf(id,peer))
    }
    @Synchronized fun attempted(letter: Letter, now: Long) {
        val delay = (10000L * (1L shl letter.attempts.coerceAtMost(5))).coerceAtMost(300000L)
        writableDatabase.execSQL("UPDATE letters SET attempts=attempts+1,next=? WHERE id=? AND peer=? AND incoming=0 AND state='PENDING'",
            arrayOf(now+delay,letter.id,letter.peer))
    }
    @Synchronized fun retry() { writableDatabase.execSQL("UPDATE letters SET next=0,attempts=0 WHERE state='PENDING'") }
    @Synchronized fun letters(incoming: Boolean? = null): List<Letter> {
        val where = if(incoming == null) "" else " WHERE incoming=" + if(incoming) "1" else "0"
        return readableDatabase.rawQuery("SELECT id,peer,name,body,incoming,created,state,attempts,next FROM letters$where ORDER BY created DESC,rowid DESC",null).use { c ->
            buildList { while(c.moveToNext()) add(Letter(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4)==1,c.getLong(5),c.getString(6),c.getInt(7),c.getLong(8))) }
        }
    }
    @Synchronized fun pending(now: Long): List<Letter> =
        readableDatabase.rawQuery("SELECT l.id,l.peer,l.name,l.body,l.incoming,l.created,l.state,l.attempts,l.next FROM letters l JOIN peers p ON p.id=l.peer WHERE l.incoming=0 AND l.state='PENDING' AND l.next<=? AND p.trusted=1 ORDER BY l.created ASC",arrayOf(now.toString())).use { c ->
            buildList { while(c.moveToNext()) add(Letter(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4)==1,c.getLong(5),c.getString(6),c.getInt(7),c.getLong(8))) }
        }
}

object Identity {
    @Synchronized fun load(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "ipmsg-identity-v1"
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(false).build())
            }.generateKeyPair()
        }
        return KeyPair(store.getCertificate(alias).publicKey,store.getKey(alias,null) as java.security.PrivateKey)
    }
}
