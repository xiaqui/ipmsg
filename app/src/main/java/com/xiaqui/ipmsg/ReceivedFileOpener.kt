package com.xiaqui.ipmsg

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap

/** Builds a least-privilege, user-initiated VIEW request for a received SAF document. */
object ReceivedFileOpener {
    const val APK_MIME="application/vnd.android.package-archive"

    fun mime(resolver:ContentResolver,uri:Uri,name:String,offered:String):String {
        if(name.substringAfterLast('.',"").equals("apk",true)) return APK_MIME
        val provider=runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        val extension=name.substringAfterLast('.',"")
        val byName=extension.takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }.orEmpty()
        return listOf(provider,offered,byName).firstOrNull {
            it.isNotBlank()&&it!="application/octet-stream"&&it.contains('/')
        } ?: "application/octet-stream"
    }

    fun viewIntent(resolver:ContentResolver,uri:Uri,name:String,offered:String):Intent {
        require(uri.scheme==ContentResolver.SCHEME_CONTENT) { "INVALID_URI" }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,mime(resolver,uri,name,offered))
            clipData=ClipData.newUri(resolver,name,uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }
}
