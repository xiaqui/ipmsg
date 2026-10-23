package com.xiaqui.ipmsg

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileMeta(val fileId:String,val name:String,val mime:String,val size:Long,
    val sha256:String,val source:String="")

object FileContract {
    const val CAPABILITY="file-transfer/1"
    const val CHUNK=32768
    const val MAX_FILES=32
    const val MAX_FILE=2147483648L
    const val MAX_TOTAL=4294967296L
    val EMPTY_SHA=sha(ByteArray(0))
    private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val hash=Regex("[0-9a-f]{64}")
    private val reserved=(listOf("CON","PRN","AUX","NUL")+(1..9).flatMap { listOf("COM$it","LPT$it") }).toSet()
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun validUuid(value:String)=uuid.matches(value)
    fun validHash(value:String)=hash.matches(value)
    fun validName(value:String):Boolean {
        if(value.isEmpty() || value=="." || value==".." || value.toByteArray(Charsets.UTF_8).size>255 ||
            value.endsWith(" ") || value.endsWith(".") || value.any { it.code<32 || it in "/\\:\u0000" }) return false
        return value.substringBefore('.').uppercase() !in reserved
    }
    fun validate(files:List<FileMeta>) {
        require(files.size in 1..MAX_FILES && files.map { it.fileId }.distinct().size==files.size)
        var total=0L
        files.forEach { require(validUuid(it.fileId)&&validName(it.name)&&it.mime.length<=255&&
            it.size in 0..MAX_FILE&&validHash(it.sha256));require(total<=MAX_TOTAL-it.size);total+=it.size }
        require(total<=MAX_TOTAL)
    }
    fun parseFiles(array:JSONArray):List<FileMeta> = buildList {
        for(index in 0 until array.length()) array.getJSONObject(index).let {
            add(FileMeta(it.getString("fileId"),it.getString("name"),it.getString("mime"),it.getLong("size"),it.getString("sha256")))
        }
    }.also(::validate)
    fun json(file:TransferFile)=JSONObject().put("fileId",file.fileId).put("name",file.name)
        .put("mime",file.mime).put("size",file.size).put("sha256",file.sha256)
}

class FileTransferManager(private val context:Context,private val store:Store,
    private val changed:()->Unit,private val offered:(String,String)->Unit) {
    private data class DigestState(val offset:Long,val digest:MessageDigest)
    private val sourceDigests=ConcurrentHashMap<String,DigestState>()
    private val targetDigests=ConcurrentHashMap<String,DigestState>()
    private val resumed=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val finalizing=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun key(transfer:String,file:String)="$transfer:$file"
    private fun hex(digest:MessageDigest)=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }

    fun describe(uris:List<Uri>):List<FileMeta> {
        require(uris.size in 1..FileContract.MAX_FILES)
        val resolver=context.contentResolver
        val result=uris.map { uri ->
            var name="";var size=-1L
            resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                if(c.moveToFirst()) { name=c.getString(0)?:"";if(!c.isNull(1)) size=c.getLong(1) }
            }
            require(size>=0&&FileContract.validName(name)&&size<=FileContract.MAX_FILE)
            val digest=MessageDigest.getInstance("SHA-256");var read=0L
            resolver.openInputStream(uri)!!.use { input ->
                val buffer=ByteArray(1024*1024)
                while(true) { val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count);read+=count }
            }
            require(read==size)
            FileMeta(UUID.randomUUID().toString(),name,resolver.getType(uri)?:"application/octet-stream",
                size,hex(digest),uri.toString())
        }
        FileContract.validate(result);return result
    }
    fun queue(peerIds:Collection<String>,files:List<FileMeta>,selfId:String="",selfName:String=""):List<String> {
        FileContract.validate(files)
        val peers=store.peers().filter { it.trusted }.associateBy { it.id }
        require(peerIds.isNotEmpty()&&peerIds.all { it==selfId||peers[it]?.fileCapable==true })
        return peerIds.distinct().map { peerId -> UUID.randomUUID().toString().also {
            if(peerId==selfId) store.createSelfTransfer(it,selfId,selfName,files)
            else store.createOutgoingTransfer(it,peers.getValue(peerId),files)
        } }
    }
    fun accept(transfer:Transfer,tree:Uri) {
        val files=store.transferFiles(transfer.id);val incoming=File(context.filesDir,"file-transfer")
        check(incoming.exists()||incoming.mkdirs())
        val needed=files.sumOf { it.size-it.offset }+64L*1024*1024
        require(StatFs(incoming.absolutePath).availableBytes>=needed) { "NO_SPACE" }
        val temps=files.associate { file -> file.fileId to File(incoming,"${transfer.id}-${file.fileId}.part").apply {
            if(!exists()) outputStream().use { it.fd.sync() }
            else if(length()!=file.offset) { outputStream().use { it.fd.sync() };store.updateFileProgress(transfer.id,file.fileId,0,FileContract.EMPTY_SHA,"PENDING") }
            require(isFile)
        }.absolutePath }
        store.acceptTransfer(transfer.id,transfer.peer,tree.toString(),temps);changed()
        if(files.any { it.source.isNotEmpty() }) Thread { copySelf(transfer) }.start()
    }
    fun decline(transfer:Transfer) { store.setTransferState(transfer.id,transfer.peer,transfer.incoming,
        if(transfer.incoming) "DECLINED" else "CANCEL_PENDING","CANCELLED",if(transfer.incoming) null else 0L);changed() }

    fun handle(peer:Peer,body:JSONObject):JSONObject {
        val type=body.getString("type")
        if(type=="CAPS") {
            val request=body.getString("requestId");require(FileContract.validUuid(request)&&body.getString("protocol")==FileContract.CAPABILITY)
            return caps(request)
        }
        val id=body.getString("transferId");require(FileContract.validUuid(id))
        if(type in setOf("FILE_CHUNK","FILE_RESUME","FILE_COMPLETE")) {
            val existing=runCatching { store.transfer(id,peer.id,true) }.getOrNull()
            if(existing?.state in setOf("CANCELLED","DECLINED")) return error(id,"CANCELLED",false,body.optString("fileId"))
        }
        return when(type) {
            "FILE_OFFER" -> offer(peer,id,body)
            "FILE_CHUNK" -> chunk(peer,id,body)
            "FILE_RESUME" -> resume(peer,id,body)
            "FILE_COMPLETE" -> complete(peer,id,body)
            "FILE_CANCEL" -> { store.setTransferState(id,peer.id,true,"CANCELLED","CANCELLED");changed();error(id,"CANCELLED",false) }
            "FILE_ACCEPT" -> { val transfer=store.transfer(id,peer.id,false);applyAccept(transfer,body)
                val first=body.getJSONArray("selected").getJSONObject(0)
                JSONObject().put("type","FILE_RESUME").put("transferId",id).put("fileId",first.getString("fileId"))
                    .put("offset",first.getLong("offset")).put("prefixSha256",first.getString("prefixSha256")) }
            else -> throw IllegalArgumentException("unsupported file frame")
        }
    }
    private fun caps(request:String)=JSONObject().put("type","CAPS").put("requestId",request)
        .put("protocol",FileContract.CAPABILITY).put("maxFiles",FileContract.MAX_FILES)
        .put("maxFileBytes",FileContract.MAX_FILE).put("chunkBytes",FileContract.CHUNK)
    private fun error(id:String,code:String,retryable:Boolean,file:String="")=JSONObject().put("type","FILE_ERROR")
        .put("transferId",id).put("code",code).put("retryable",retryable).also { if(file.isNotEmpty()) it.put("fileId",file) }
    private fun offer(peer:Peer,id:String,body:JSONObject):JSONObject {
        // FILE_OFFER is retried while waiting for consent, and can recur after consent.
        // Only the first receipt of a transfer should raise a user notification.
        val firstOffer=runCatching { store.transfer(id,peer.id,true) }.isFailure
        val transfer=store.receiveOffer(id,peer,FileContract.parseFiles(body.getJSONArray("files")))
        changed()
        if(firstOffer && transfer.state=="WAITING_ACCEPT") offered(peer.name,id)
        if(transfer.state=="DECLINED") return error(id,"DECLINED",false)
        if(transfer.state !in setOf("TRANSFERRING","PAUSED","COMPLETED")) return error(id,"TIMEOUT",true)
        val selected=JSONArray();store.transferFiles(id).filter { it.state!="COMPLETED" }.forEach { file ->
            selected.put(JSONObject().put("fileId",file.fileId).put("offset",file.offset).put("prefixSha256",file.prefixSha256)) }
        return JSONObject().put("type","FILE_ACCEPT").put("transferId",id).put("selected",selected)
    }
    private fun incoming(id:String,peer:String,fileId:String):Pair<Transfer,TransferFile> {
        val transfer=store.transfer(id,peer,true);require(transfer.state in setOf("TRANSFERRING","PAUSED"))
        return transfer to store.transferFiles(id).first { it.fileId==fileId&&it.temp.isNotEmpty() }
    }
    private fun digestFor(file:TransferFile,target:Boolean):MessageDigest {
        val cache=if(target) targetDigests else sourceDigests;val cacheKey=key(file.transferId,file.fileId)
        cache[cacheKey]?.takeIf { it.offset==file.offset }?.let { return it.digest.clone() as MessageDigest }
        val digest=MessageDigest.getInstance("SHA-256")
        val input=if(target) FileInputStream(file.temp) else context.contentResolver.openInputStream(Uri.parse(file.source))!!
        input.use { stream -> var left=file.offset;val buffer=ByteArray(1024*1024)
            while(left>0) { val count=stream.read(buffer,0,minOf(buffer.size.toLong(),left).toInt());check(count>0);digest.update(buffer,0,count);left-=count }
        }
        cache[cacheKey]=DigestState(file.offset,digest.clone() as MessageDigest);return digest
    }
    private fun chunk(peer:Peer,id:String,body:JSONObject):JSONObject {
        val fileId=body.getString("fileId");val (_,file)=incoming(id,peer.id,fileId)
        val offset=body.getLong("offset");val seq=body.getLong("seq")
        if(offset<0||seq!=offset/FileContract.CHUNK) return error(id,"OFFSET_MISMATCH",false,fileId)
        val data=runCatching { Protocol.decode(body.getString("data")) }.getOrElse { return error(id,"HASH_MISMATCH",false,fileId) }
        if(data.isEmpty()||data.size>FileContract.CHUNK||offset+data.size>file.size||FileContract.sha(data)!=body.getString("sha256"))
            return error(id,"HASH_MISMATCH",false,fileId)
        val target=File(file.temp)
        if(offset<file.offset) {
            val old=ByteArray(data.size);FileInputStream(target).use { it.channel.position(offset);if(it.read(old)!=old.size||!old.contentEquals(data)) return error(id,"OFFSET_MISMATCH",false,fileId) }
        } else if(offset!=file.offset) return error(id,"OFFSET_MISMATCH",false,fileId)
        else {
            val digest=digestFor(file,true);digest.update(data)
            FileOutputStream(target,true).use { out -> out.write(data);out.flush();out.fd.sync() }
            val next=offset+data.size;val prefix=hex(digest.clone() as MessageDigest)
            store.updateFileProgress(id,fileId,next,prefix);targetDigests[key(id,fileId)]=DigestState(next,digest);changed()
        }
        val current=store.transferFiles(id).first { it.fileId==fileId }
        return JSONObject().put("type","FILE_CHUNK_ACK").put("transferId",id).put("fileId",fileId)
            .put("nextOffset",current.offset).put("prefixSha256",current.prefixSha256)
    }
    private fun resume(peer:Peer,id:String,body:JSONObject):JSONObject {
        val fileId=body.getString("fileId");val (_,file)=incoming(id,peer.id,fileId)
        return JSONObject().put("type","FILE_CHUNK_ACK").put("transferId",id).put("fileId",fileId)
            .put("nextOffset",file.offset).put("prefixSha256",file.prefixSha256)
    }
    private fun uniqueName(tree:Uri,wanted:String):String {
        val resolver=context.contentResolver;val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val names=mutableSetOf<String>();resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { c -> while(c.moveToNext()) names.add(c.getString(0)) }
        val dot=wanted.lastIndexOf('.');val stem=if(dot>0) wanted.substring(0,dot) else wanted;val suffix=if(dot>0) wanted.substring(dot) else ""
        for(index in 0..9999) { val candidate=if(index==0) wanted else "$stem ($index)$suffix";if(candidate !in names) return candidate }
        throw IllegalStateException("name collision")
    }
    private fun complete(peer:Peer,id:String,body:JSONObject):JSONObject {
        val fileId=body.getString("fileId");val transfer=store.transfer(id,peer.id,true)
        val known=store.transferFiles(id).firstOrNull { it.fileId==fileId } ?:return error(id,"INVALID_METADATA",false,fileId)
        if(known.state=="COMPLETED"&&body.getString("sha256")==known.sha256)
            return JSONObject().put("type","FILE_COMPLETE_ACK").put("transferId",id).put("fileId",fileId).put("sha256",known.sha256)
        // A persisted FINALIZING may survive a process death; restart it if no worker exists.
        if(transfer.state=="FINALIZING" && key(id,fileId) in finalizing) return error(id,"TIMEOUT",true,fileId)
        val (_,file)=if(transfer.state=="FINALIZING") transfer to known else incoming(id,peer.id,fileId)
        if(body.getLong("size")!=file.size||body.getString("sha256")!=file.sha256||file.offset!=file.size) return error(id,"HASH_MISMATCH",false,fileId)
        val (actualHash,actualLength)=runCatching { hashFile(FileInputStream(file.temp)) }
            .getOrElse { return error(id,"IO_ERROR",true,fileId) }
        if(actualLength!=file.size||actualHash!=file.sha256) return error(id,"HASH_MISMATCH",false,fileId)
        val task=key(id,fileId)
        if(finalizing.add(task)) {
            store.setTransferState(id,peer.id,true,"FINALIZING")
            Thread { try { finalizeIncoming(transfer,file) }
                catch(error:Exception) { store.setTransferState(id,peer.id,true,"PAUSED","IO_ERROR") }
                finally { finalizing.remove(task);changed() }
            }.start()
        }
        return error(id,"TIMEOUT",true,fileId)
    }
    private fun finalizeIncoming(transfer:Transfer,file:TransferFile) {
        val id=transfer.id;val fileId=file.fileId
        val tree=Uri.parse(transfer.destination);val resolver=context.contentResolver
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val tempName=".ipmsg-${id.take(8)}-${fileId.take(8)}.part"
        val document=DocumentsContract.createDocument(resolver,parent,"application/octet-stream",tempName)
            ?:throw IllegalStateException("create failed")
        try {
            resolver.openFileDescriptor(document,"w")!!.use { descriptor ->
                FileInputStream(file.temp).use { input -> FileOutputStream(descriptor.fileDescriptor).use { output -> input.copyTo(output);output.flush();descriptor.fileDescriptor.sync() } }
            }
            val finalUri=DocumentsContract.renameDocument(resolver,document,uniqueName(tree,file.name))
                ?:throw IllegalStateException("rename unsupported")
            store.updateFileProgress(id,fileId,file.size,file.sha256,"COMPLETED",finalUri.toString());File(file.temp).delete()
            store.setTransferState(id,transfer.peer,true,
                if(store.transferFiles(id).all { it.state=="COMPLETED" }) "COMPLETED" else "TRANSFERRING")
        } catch(error:Exception) { runCatching { DocumentsContract.deleteDocument(resolver,document) };throw error }
    }
    private fun copySelf(original:Transfer) {
        try {
            store.transferFiles(original.id).forEach { file ->
                val digest=MessageDigest.getInstance("SHA-256");var count=0L
                context.contentResolver.openInputStream(Uri.parse(file.source))!!.use { input ->
                    FileOutputStream(file.temp,false).use { output ->
                        val buffer=ByteArray(1024*1024)
                        while(true) { val read=input.read(buffer);if(read<0) break;output.write(buffer,0,read);digest.update(buffer,0,read);count+=read
                            store.updateFileProgress(original.id,file.fileId,count,hex(digest.clone() as MessageDigest));changed() }
                        output.flush();output.fd.sync()
                    }
                }
                require(count==file.size&&hex(digest.clone() as MessageDigest)==file.sha256)
                finalizeIncoming(store.transfer(original.id,original.peer,true),
                    store.transferFiles(original.id).first { it.fileId==file.fileId })
            }
        } catch(error:Exception) { store.setTransferState(original.id,original.peer,true,"FAILED","IO_ERROR");changed() }
    }
    private fun hashFile(input:InputStream):Pair<String,Long> { val digest=MessageDigest.getInstance("SHA-256");var count=0L
        input.use { stream -> val buffer=ByteArray(1024*1024);while(true) { val read=stream.read(buffer);if(read<0) break;digest.update(buffer,0,read);count+=read } }
        return hex(digest) to count }
    private fun skip(input:InputStream,amount:Long) { var left=amount;val buffer=ByteArray(65536);while(left>0) { val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());check(n>0);left-=n } }
    private fun applyAccept(transfer:Transfer,body:JSONObject) {
        val files=store.transferFiles(transfer.id).associateBy { it.fileId };val selected=body.getJSONArray("selected")
        require(selected.length()==files.size)
        val seen=mutableSetOf<String>()
        for(index in 0 until selected.length()) selected.getJSONObject(index).let { item ->
            val id=item.getString("fileId");val file=files.getValue(id);val offset=item.getLong("offset");val prefix=item.getString("prefixSha256")
            require(seen.add(id)&&offset in 0..file.size&&FileContract.validHash(prefix));store.updateFileProgress(transfer.id,id,offset,prefix)
        }
        store.setTransferState(transfer.id,transfer.peer,false,"TRANSFERRING",next=0L)
    }
    fun process(transfer:Transfer,peer:Peer,exchange:(JSONObject)->JSONObject) {
        try {
            require(peer.trusted&&peer.fileCapable) { "UNSUPPORTED" }
            if(transfer.state=="CANCEL_PENDING") {
                exchange(JSONObject().put("type","FILE_CANCEL").put("transferId",transfer.id).put("reason","USER_CANCELLED"))
                store.setTransferState(transfer.id,peer.id,false,"CANCELLED","CANCELLED");return
            }
            if(transfer.state=="QUEUED") { val request=UUID.randomUUID().toString();val answer=exchange(caps(request))
                require(answer.getString("type")=="CAPS"&&answer.getString("requestId")==request) { "UNSUPPORTED" }
                store.setTransferState(transfer.id,peer.id,false,"OFFERING",next=0L);return }
            val files=store.transferFiles(transfer.id)
            if(transfer.state in setOf("OFFERING","WAITING_ACCEPT")) {
                val array=JSONArray();files.forEach { array.put(FileContract.json(it)) }
                val answer=exchange(JSONObject().put("type","FILE_OFFER").put("transferId",transfer.id).put("files",array))
                if(answer.getString("type")=="FILE_ACCEPT") applyAccept(transfer,answer)
                else if(answer.getString("type")=="FILE_ERROR"&&!answer.optBoolean("retryable"))
                    store.setTransferState(transfer.id,peer.id,false,"DECLINED",answer.optString("code","DECLINED"))
                else store.setTransferState(transfer.id,peer.id,false,"WAITING_ACCEPT")
                return
            }
            var file=files.firstOrNull { it.state!="COMPLETED" }
            if(file==null) { store.setTransferState(transfer.id,peer.id,false,"COMPLETED");return }
            val cacheKey=key(transfer.id,file.fileId)
            if(file.offset<file.size&&resumed.add(cacheKey)) {
                val answer=exchange(JSONObject().put("type","FILE_RESUME").put("transferId",transfer.id).put("fileId",file.fileId)
                    .put("offset",file.offset).put("prefixSha256",file.prefixSha256))
                if(answer.optString("type")=="FILE_ERROR"&&answer.optString("code") in setOf("CANCELLED","DECLINED")) {
                    store.setTransferState(transfer.id,peer.id,false,"CANCELLED",answer.optString("code"));return }
                if(answer.optString("type")=="FILE_ERROR"&&answer.optBoolean("retryable")) return
                require(answer.getString("type")=="FILE_CHUNK_ACK") { "OFFSET_MISMATCH" }
                val offset=answer.getLong("nextOffset");val prefix=answer.getString("prefixSha256")
                require(offset in 0..file.size&&FileContract.validHash(prefix)) { "OFFSET_MISMATCH" }
                store.updateFileProgress(transfer.id,file.fileId,offset,prefix);return
            }
            val activeId=file.fileId
            file=store.transferFiles(transfer.id).first { it.fileId==activeId }
            if(file.offset<file.size) {
                val uri=Uri.parse(file.source)
                val data=context.contentResolver.openFileDescriptor(uri,"r")!!.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        input.channel.position(file.offset)
                        input.readBytesBounded(FileContract.CHUNK)
                    }
                }
                require(data.isNotEmpty()) { "SOURCE_CHANGED" }
                val answer=exchange(JSONObject().put("type","FILE_CHUNK").put("transferId",transfer.id).put("fileId",file.fileId)
                    .put("seq",file.offset/FileContract.CHUNK).put("offset",file.offset).put("data",Protocol.encode(data)).put("sha256",FileContract.sha(data)))
                if(answer.optString("type")=="FILE_ERROR"&&answer.optString("code") in setOf("CANCELLED","DECLINED")) {
                    store.setTransferState(transfer.id,peer.id,false,"CANCELLED",answer.optString("code"));return }
                if(answer.optString("type")=="FILE_ERROR"&&answer.optBoolean("retryable")) return
                val expected=file.offset+data.size;require(answer.getString("type")=="FILE_CHUNK_ACK"&&answer.getLong("nextOffset")==expected) { "OFFSET_MISMATCH" }
                val digest=digestFor(file,false);digest.update(data);val prefix=hex(digest.clone() as MessageDigest)
                require(answer.getString("prefixSha256")==prefix) { "HASH_MISMATCH" }
                store.updateFileProgress(transfer.id,file.fileId,expected,prefix);sourceDigests[cacheKey]=DigestState(expected,digest);return
            }
            val (hash,count)=hashFile(context.contentResolver.openInputStream(Uri.parse(file.source))!!)
            require(count==file.size&&hash==file.sha256) { "SOURCE_CHANGED" }
            val answer=exchange(JSONObject().put("type","FILE_COMPLETE").put("transferId",transfer.id).put("fileId",file.fileId)
                .put("size",file.size).put("sha256",file.sha256))
            if(answer.optString("type")=="FILE_ERROR"&&answer.optString("code") in setOf("CANCELLED","DECLINED")) {
                store.setTransferState(transfer.id,peer.id,false,"CANCELLED",answer.optString("code"));return }
            if(answer.optString("type")=="FILE_ERROR"&&answer.optBoolean("retryable")) return
            require(answer.getString("type")=="FILE_COMPLETE_ACK"&&answer.getString("sha256")==file.sha256) { "HASH_MISMATCH" }
            store.updateFileProgress(transfer.id,file.fileId,file.size,file.sha256,"COMPLETED")
            if(store.transferFiles(transfer.id).all { it.state=="COMPLETED" }) store.setTransferState(transfer.id,peer.id,false,"COMPLETED")
        } catch(error:Exception) {
            val code=error.message?.takeIf { it in setOf("UNSUPPORTED","SOURCE_CHANGED","OFFSET_MISMATCH","HASH_MISMATCH") }?:"IO_ERROR"
            store.setTransferState(transfer.id,peer.id,false,if(code in setOf("UNSUPPORTED","SOURCE_CHANGED","HASH_MISMATCH")) "FAILED" else transfer.state,code)
        } finally { changed() }
    }
    private fun InputStream.readBytesBounded(max:Int):ByteArray { val buffer=ByteArray(max);var count=0
        while(count<max) { val read=read(buffer,count,max-count);if(read<0) break;count+=read }
        return buffer.copyOf(count) }
}
