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
    val host: String, val seen: Long, val fileCapable: Boolean = false) {
    fun online(now: Long) = seen > 0 && now - seen in 0..35000
}
data class Letter(val id: String, val peer: String, val name: String, val body: String,
    val incoming: Boolean, val created: Long, val state: String, val attempts: Int, val next: Long)
data class Transfer(val id:String,val peer:String,val peerName:String,val incoming:Boolean,
    val state:String,val created:Long,val updated:Long,val destination:String,val error:String,
    val attempts:Int,val next:Long)
data class TransferFile(val transferId:String,val fileId:String,val name:String,val mime:String,
    val size:Long,val sha256:String,val source:String,val temp:String,val finalPath:String,
    val offset:Long,val prefixSha256:String,val state:String)

/** All delivery transitions commit synchronously before an ACK is emitted. */
class Store(context: Context) : SQLiteOpenHelper(context.applicationContext, "ipmsg.db", null, 3) {
    companion object { val deliveryGate = Any() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE peers(id TEXT PRIMARY KEY,name TEXT NOT NULL,pub TEXT NOT NULL,trusted INTEGER NOT NULL,host TEXT NOT NULL,seen INTEGER NOT NULL,files INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE letters(id TEXT NOT NULL,peer TEXT NOT NULL,name TEXT NOT NULL,body TEXT NOT NULL,incoming INTEGER NOT NULL,created INTEGER NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id,peer,incoming))")
        db.execSQL("CREATE INDEX pending_due ON letters(incoming,state,next,created)")
        tombstones(db)
        transferTables(db)
    }
    private fun tombstones(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS deleted_received(id TEXT NOT NULL,peer TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(id,peer))")
    private fun transferTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS file_transfers(id TEXT NOT NULL,peer TEXT NOT NULL,peer_name TEXT NOT NULL,incoming INTEGER NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,destination TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id,peer,incoming))")
        db.execSQL("CREATE TABLE IF NOT EXISTS transfer_files(transfer_id TEXT NOT NULL,file_id TEXT NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,source TEXT NOT NULL DEFAULT '',temp TEXT NOT NULL DEFAULT '',final TEXT NOT NULL DEFAULT '',offset INTEGER NOT NULL DEFAULT 0,prefix TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(transfer_id,file_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS transfer_due ON file_transfers(incoming,state,next,updated)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        tombstones(db)
        if(oldVersion<3) {
            runCatching { db.execSQL("ALTER TABLE peers ADD COLUMN files INTEGER NOT NULL DEFAULT 0") }
            transferTables(db)
        }
    }
    private fun digest(body: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun deleteLetter(letter: Letter) = synchronized(deliveryGate) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            if(letter.incoming) db.execSQL("INSERT OR IGNORE INTO deleted_received VALUES(?,?,?)",
                arrayOf(letter.id,letter.peer,digest(letter.body)))
            db.delete("letters","id=? AND peer=? AND incoming=?",
                arrayOf(letter.id,letter.peer,if(letter.incoming) "1" else "0"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun writeIfPending(letter: Letter, action: () -> Unit): Boolean = synchronized(deliveryGate) {
        val pending=readableDatabase.rawQuery("SELECT 1 FROM letters WHERE id=? AND peer=? AND incoming=0 AND state='PENDING'",
            arrayOf(letter.id,letter.peer)).use { it.moveToFirst() }
        if(pending) action()
        pending
    }
    @Synchronized fun peers(): List<Peer> = readableDatabase.rawQuery(
        "SELECT id,name,pub,trusted,host,seen,files FROM peers ORDER BY trusted DESC,name", null).use { c ->
        buildList { while(c.moveToNext()) add(Peer(c.getString(0),c.getString(1),c.getString(2),
            c.getInt(3) == 1,c.getString(4),c.getLong(5),c.getInt(6)==1)) }
    }
    @Synchronized fun discover(hello: Protocol.Hello, host: String, now: Long): Boolean {
        // Candidate admission is separate from approved peers. One source cannot reserve every slot.
        writableDatabase.delete("peers", "trusted=0 AND seen<?", arrayOf((now - 120000).toString()))
        val current = peers()
        val old = current.find { it.id == hello.id }
        if (old != null && old.key != hello.key) return false
        if (old?.trusted != true) {
            val candidates = current.filter { !it.trusted }
            if (candidates.any { it.host == host && it.id != hello.id }) return false
            if (old == null && candidates.size >= 16) {
                candidates.sortedBy { it.seen }.take(candidates.size - 15).forEach {
                    // The UI can approve concurrently; never evict a peer that became approved.
                    writableDatabase.delete("peers","id=? AND trusted=0",arrayOf(it.id))
                }
            }
        }
        val db = writableDatabase
        // Insert once with default trust=false. Discovery never writes an existing trust flag.
        val initial = ContentValues().apply {
            put("id",hello.id);put("name",hello.name);put("pub",hello.key)
            put("trusted",0);put("host",host);put("seen",now)
            put("files",if(Protocol.FILE_CAPABILITY in hello.capabilities) 1 else 0)
        }
        db.insertWithOnConflict("peers",null,initial,SQLiteDatabase.CONFLICT_IGNORE)
        val updated = db.update("peers",ContentValues().apply {
            put("name",hello.name);put("host",host);put("seen",now)
            put("files",if(Protocol.FILE_CAPABILITY in hello.capabilities) 1 else 0)
        },"id=? AND pub=?",arrayOf(hello.id,hello.key))
        return updated == 1
    }

    @Synchronized fun trust(id: String, value: Boolean) {
        writableDatabase.update("peers", ContentValues().apply { put("trusted",if(value) 1 else 0) },
            "id=?",arrayOf(id))
    }
    @Synchronized fun offline() { writableDatabase.execSQL("UPDATE peers SET seen=0") }
    @Synchronized fun offline(ids: Collection<String>) {
        if(ids.isEmpty()) return
        val values=ids.distinct()
        val placeholders=values.joinToString(",") { "?" }
        writableDatabase.execSQL("UPDATE peers SET seen=0 WHERE id IN ($placeholders)",values.toTypedArray())
    }
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
            db.rawQuery("SELECT digest FROM deleted_received WHERE id=? AND peer=?",arrayOf(id,peer)).use { c ->
                if(c.moveToFirst()) {
                    require(c.getString(0)==digest(body))
                    db.setTransactionSuccessful()
                    return false
                }
            }
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
    /**
     * Reconnection/manual wake may shorten a long backoff, but never below ten seconds from now.
     * Already-due/new letters stay due and attempt history is preserved.
     */
    @Synchronized fun retry(now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("UPDATE letters SET next=MIN(next,?) WHERE state='PENDING'",
            arrayOf(now+10000L))
    }
    @Synchronized fun retry(peerIds: Collection<String>, now: Long = System.currentTimeMillis()) {
        if(peerIds.isEmpty()) return
        val values=peerIds.distinct()
        val placeholders=values.joinToString(",") { "?" }
        val args=mutableListOf<Any>(now+10000L).apply { addAll(values) }.toTypedArray()
        writableDatabase.execSQL(
            "UPDATE letters SET next=MIN(next,?) WHERE state='PENDING' AND peer IN ($placeholders)",args)
    }
    @Synchronized fun letters(incoming: Boolean? = null): List<Letter> {
        val where = if(incoming == null) "" else " WHERE incoming=" + if(incoming) "1" else "0"
        return readableDatabase.rawQuery("SELECT id,peer,name,body,incoming,created,state,attempts,next FROM letters$where ORDER BY created DESC,rowid DESC",null).use { c ->
            buildList { while(c.moveToNext()) add(Letter(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4)==1,c.getLong(5),c.getString(6),c.getInt(7),c.getLong(8))) }
        }
    }
    @Synchronized fun pending(now: Long): List<Letter> =
        readableDatabase.rawQuery("SELECT l.id,l.peer,l.name,l.body,l.incoming,l.created,l.state,l.attempts,l.next FROM letters l JOIN peers p ON p.id=l.peer WHERE l.incoming=0 AND l.state='PENDING' AND l.next<=? AND p.trusted=1 ORDER BY l.next ASC,l.created ASC",arrayOf(now.toString())).use { c ->
            buildList { while(c.moveToNext()) add(Letter(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getInt(4)==1,c.getLong(5),c.getString(6),c.getInt(7),c.getLong(8))) }
        }

    @Synchronized fun createOutgoingTransfer(id:String,peer:Peer,files:List<FileMeta>,now:Long=System.currentTimeMillis()) {
        require(peer.trusted && peer.fileCapable)
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(id,peer.id,peer.name,0,"QUEUED",now,now,"","",0,0))
            files.forEach { file -> db.execSQL("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(id,file.fileId,file.name,file.mime,file.size,file.sha256,file.source,"","",0,digest(""),"PENDING")) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun createSelfTransfer(id:String,self:String,selfName:String,files:List<FileMeta>,now:Long=System.currentTimeMillis()) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(id,self,selfName,1,"WAITING_ACCEPT",now,now,"","",0,0))
            files.forEach { file -> db.execSQL("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(id,file.fileId,file.name,file.mime,file.size,file.sha256,file.source,"","",0,digest(""),"PENDING")) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun receiveOffer(id:String,peer:Peer,files:List<FileMeta>,now:Long=System.currentTimeMillis()):Transfer {
        require(peer.trusted)
        val db=writableDatabase;db.beginTransaction()
        try {
            val old=db.rawQuery("SELECT 1 FROM file_transfers WHERE id=? AND peer=? AND incoming=1",arrayOf(id,peer.id)).use { it.moveToFirst() }
            if(old) {
                val existing=transferFiles(id)
                require(existing.map { listOf(it.fileId,it.name,it.size.toString(),it.sha256) } ==
                    files.map { listOf(it.fileId,it.name,it.size.toString(),it.sha256) })
            } else {
                db.execSQL("INSERT INTO file_transfers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(id,peer.id,peer.name,1,"WAITING_ACCEPT",now,now,"","",0,0))
                files.forEach { file -> db.execSQL("INSERT INTO transfer_files VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(id,file.fileId,file.name,file.mime,file.size,file.sha256,"","","",0,digest(""),"PENDING")) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return transfer(id,peer.id,true)
    }
    @Synchronized fun transfer(id:String,peer:String,incoming:Boolean):Transfer = readableDatabase.rawQuery(
        "SELECT id,peer,peer_name,incoming,state,created,updated,destination,error,attempts,next FROM file_transfers WHERE id=? AND peer=? AND incoming=?",
        arrayOf(id,peer,if(incoming) "1" else "0")).use { c ->
        check(c.moveToFirst());Transfer(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getString(4),
            c.getLong(5),c.getLong(6),c.getString(7),c.getString(8),c.getInt(9),c.getLong(10))
    }
    @Synchronized fun transfers(incoming:Boolean?=null):List<Transfer> {
        val where=if(incoming==null) "" else " WHERE incoming="+if(incoming) "1" else "0"
        return readableDatabase.rawQuery("SELECT id,peer,peer_name,incoming,state,created,updated,destination,error,attempts,next FROM file_transfers$where ORDER BY updated DESC",null).use { c ->
            buildList { while(c.moveToNext()) add(Transfer(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,
                c.getString(4),c.getLong(5),c.getLong(6),c.getString(7),c.getString(8),c.getInt(9),c.getLong(10))) }
        }
    }
    @Synchronized fun transferFiles(id:String):List<TransferFile> = readableDatabase.rawQuery(
        "SELECT transfer_id,file_id,name,mime,size,sha256,source,temp,final,offset,prefix,state FROM transfer_files WHERE transfer_id=? ORDER BY rowid",
        arrayOf(id)).use { c -> buildList { while(c.moveToNext()) add(TransferFile(c.getString(0),c.getString(1),c.getString(2),
            c.getString(3),c.getLong(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getLong(9),c.getString(10),c.getString(11))) } }
    @Synchronized fun acceptTransfer(id:String,peer:String,destination:String,temps:Map<String,String>) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val values=ContentValues().apply { put("state","TRANSFERRING");put("destination",destination);put("updated",System.currentTimeMillis());put("error","") }
            check(db.update("file_transfers",values,"id=? AND peer=? AND incoming=1 AND state IN ('WAITING_ACCEPT','PAUSED')",arrayOf(id,peer))==1)
            temps.forEach { (file,temp) -> db.update("transfer_files",ContentValues().apply { put("temp",temp) },
                "transfer_id=? AND file_id=?",arrayOf(id,file)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun setTransferState(id:String,peer:String,incoming:Boolean,state:String,error:String="",next:Long?=null) {
        require(state in setOf("QUEUED","OFFERING","WAITING_ACCEPT","TRANSFERRING","PAUSED","FINALIZING","CANCEL_PENDING","COMPLETED","CANCELLED","DECLINED","FAILED"))
        writableDatabase.update("file_transfers",ContentValues().apply {
            put("state",state);put("error",error);put("updated",System.currentTimeMillis());if(next!=null) {
                put("next",next);if(next==0L) put("attempts",0)
            }
        },"id=? AND peer=? AND incoming=?",arrayOf(id,peer,if(incoming) "1" else "0"))
    }
    @Synchronized fun updateFileProgress(id:String,fileId:String,offset:Long,prefix:String,state:String="TRANSFERRING",finalPath:String="") {
        writableDatabase.update("transfer_files",ContentValues().apply {
            put("offset",offset);put("prefix",prefix);put("state",state);if(finalPath.isNotEmpty()) put("final",finalPath)
        },"transfer_id=? AND file_id=?",arrayOf(id,fileId))
        writableDatabase.update("file_transfers",ContentValues().apply {
            put("updated",System.currentTimeMillis());put("next",0);put("attempts",0);put("error","")
        },"id=?",arrayOf(id))
    }
    @Synchronized fun dueTransfers(now:Long,limit:Int=2):List<Transfer> {
        val due=readableDatabase.rawQuery("SELECT id,peer,peer_name,incoming,state,created,updated,destination,error,attempts,next FROM file_transfers WHERE incoming=0 AND state IN ('QUEUED','OFFERING','WAITING_ACCEPT','TRANSFERRING','PAUSED','CANCEL_PENDING') AND next<=? ORDER BY next,created LIMIT ?",
            arrayOf(now.toString(),limit.coerceAtLeast(0).toString())).use { c -> buildList { while(c.moveToNext()) add(Transfer(c.getString(0),c.getString(1),c.getString(2),false,c.getString(4),c.getLong(5),c.getLong(6),c.getString(7),c.getString(8),c.getInt(9),c.getLong(10))) } }
        due.forEach { item ->
            val delay=(10000L*(1L shl item.attempts.coerceAtMost(5))).coerceAtMost(300000L)
            writableDatabase.execSQL("UPDATE file_transfers SET attempts=attempts+1,next=? WHERE id=? AND peer=? AND incoming=0",
                arrayOf(now+delay,item.id,item.peer))
        }
        return due
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


