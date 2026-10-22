package com.xiaqui.ipmsg

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,34])
class FileContractTest {
    private fun valid(value: JSONObject): Boolean {
        val name=value.getString("name");val size=value.getLong("size")
        return size in 0..2147483648L && name.isNotEmpty() && name.toByteArray().size<=255 &&
            name !in listOf(".","..") && name.none { it in "/\\:" || it.code<32 } &&
            !name.endsWith(" ") && !name.endsWith(".") &&
            !name.substringBefore('.').matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
    }
    @Test fun sharedMetadataVectors() {
        val data=JSONObject(javaClass.classLoader!!.getResourceAsStream("file-transfer-v1.json")!!
            .bufferedReader().use { it.readText() })
        for(group in listOf("valid","invalid")) {
            val values=data.getJSONArray(group)
            for(index in 0 until values.length()) assertEquals(group=="valid",valid(values.getJSONObject(index)))
        }
        assertTrue(data.getInt("chunkBytes")*4/3+2048<52000)
        fun chunkValid(value: JSONObject): Boolean {
            val bytes=android.util.Base64.decode(value.getString("data"),android.util.Base64.NO_WRAP)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return value.getLong("offset")>=0 && value.getLong("offset")==value.getLong("seq")*32768 &&
                digest==value.getString("sha256") && bytes.size<=32768
        }
        assertTrue(chunkValid(data.getJSONArray("messages").getJSONObject(2)))
        val invalid=data.getJSONArray("invalidChunks")
        for(index in 0 until invalid.length()) assertFalse(chunkValid(invalid.getJSONObject(index)))
        val caps=data.getJSONArray("capabilities")
        for(index in 0 until caps.length()) {
            val sample=caps.getJSONObject(index)
            assertEquals(sample.getBoolean("supported"),sample.getJSONArray("caps").toString().contains("file-transfer/1"))
        }
    }
}
