package com.xiaqui.ipmsg

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** Versioned, bounded wire format. Discovery contains no message body. */
object Protocol {
    const val PORT = 42124
    const val MAX_FRAME = 80000
    const val MAX_TEXT = 8192
    private val random = SecureRandom()
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    fun key(text: String): PublicKey {
        require(text.length in 300..1000)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(text))).also {
            require((it as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 2048)
        }
    }
    fun id(key: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sign(data: String, key: PrivateKey): String = Signature.getInstance("SHA256withRSA").run {
        initSign(key); update(data.toByteArray(Charsets.UTF_8)); encode(sign())
    }
    fun verify(data: String, signature: String, key: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update(data.toByteArray(Charsets.UTF_8)); verify(decode(signature))
        }
    }.getOrDefault(false)
    fun hello(name: String, pair: KeyPair, now: Long): ByteArray {
        val body = JSONObject().put("v", 1).put("name", name.take(40))
            .put("key", encode(pair.public.encoded)).put("time", now).toString()
        return JSONObject().put("body", body).put("sig", sign(body, pair.private))
            .toString().toByteArray(Charsets.UTF_8)
    }
    data class Hello(val id: String, val name: String, val key: String)
    class HelloValidationException(val stage: String, cause: Throwable? = null) :
        IllegalArgumentException(stage,cause)
    fun readHello(bytes: ByteArray, now: Long): Hello {
        var stage = "サイズ"
        try {
            if(bytes.size > 4096) throw HelloValidationException(stage)
            stage = "JSON"
            val packet = JSONObject(String(bytes, Charsets.UTF_8))
            val body = packet.getString("body")
            val data = JSONObject(body)
            stage = "バージョン"
            if(data.getInt("v") != 1) throw HelloValidationException(stage)
            stage = "時刻"
            val time = data.getLong("time")
            if(time !in (now - 120000)..(now + 120000)) throw HelloValidationException(stage)
            stage = "公開鍵"
            val publicKey = key(data.getString("key"))
            stage = "署名"
            if(!verify(body, packet.getString("sig"), publicKey)) throw HelloValidationException(stage)
            stage = "端末名"
            return Hello(id(publicKey), data.getString("name").take(40), encode(publicKey.encoded))
        } catch(e: HelloValidationException) {
            throw e
        } catch(e: Exception) {
            throw HelloValidationException(stage,e)
        }
    }
    /** RSA-OAEP wraps a fresh AES-256 key; GCM authenticates ciphertext; RSA pins sender. */
    fun seal(body: JSONObject, sender: KeyPair, recipient: PublicKey): ByteArray {
        val plain = body.put("v", 1).put("from", id(sender.public)).put("to", id(recipient)).toString()
        require(plain.toByteArray(Charsets.UTF_8).size <= 52000)
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
        }.doFinal(plain.toByteArray(Charsets.UTF_8))
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, recipient)
        }.doFinal(aes.encoded)
        val payload = JSONObject().put("from", id(sender.public)).put("iv", encode(iv))
            .put("key", encode(wrapped)).put("cipher", encode(cipher)).toString()
        return JSONObject().put("payload", payload).put("sig", sign(payload, sender.private))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FRAME) }
    }
    fun sender(frame: ByteArray): String = JSONObject(JSONObject(String(frame, Charsets.UTF_8))
        .getString("payload")).getString("from").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    fun open(frame: ByteArray, receiver: KeyPair, trusted: PublicKey): JSONObject {
        require(frame.size <= MAX_FRAME)
        val envelope = JSONObject(String(frame, Charsets.UTF_8))
        val payload = envelope.getString("payload")
        require(verify(payload, envelope.getString("sig"), trusted))
        val data = JSONObject(payload)
        require(data.getString("from") == id(trusted))
        val aes = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, receiver.private); doFinal(decode(data.getString("key")))
        }
        val iv = decode(data.getString("iv"))
        require(iv.size == 12 && aes.size == 32)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            doFinal(decode(data.getString("cipher")))
        }
        return JSONObject(String(plain, Charsets.UTF_8)).also {
            require(it.getInt("v") == 1 && it.getString("to") == id(receiver.public))
            require(it.getString("from") == id(trusted))
        }
    }
    fun write(out: DataOutputStream, bytes: ByteArray) {
        require(bytes.size in 1..MAX_FRAME)
        out.writeInt(bytes.size); out.write(bytes); out.flush()
    }
    fun read(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..MAX_FRAME)
        return ByteArray(size).also(input::readFully)
    }
    fun onPrefix(address: java.net.InetAddress, network: java.net.InetAddress, prefix: Int): Boolean {
        val a = address.address; val b = network.address
        if(a.size != 4 || b.size != 4 || prefix !in 1..32 || address.isMulticastAddress || address.isLoopbackAddress) return false
        return a.indices.all { index ->
            val bits = (prefix - index*8).coerceIn(0,8)
            val mask = (255 shl (8-bits)) and 255
            (a[index].toInt() and mask) == (b[index].toInt() and mask)
        }
    }
    fun validText(body: String) = body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= MAX_TEXT
    fun validMessageId(id: String) = id.matches(Regex("[0-9a-f-]{36}"))
}
