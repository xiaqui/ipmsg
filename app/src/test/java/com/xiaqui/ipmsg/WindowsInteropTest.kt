package com.xiaqui.ipmsg

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class WindowsInteropTest {
    private fun privateKey(pem: String) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----","")
            .replace("-----END PRIVATE KEY-----","").replace(Regex("\\s"),""))))

    @Test fun windowsFixtureIsAcceptedByAndroidV1() {
        val text=javaClass.classLoader!!.getResourceAsStream("protocol-v1-windows-vector.json")!!
            .bufferedReader().use { it.readText() }
        val vector=JSONObject(text)
        val hello=Protocol.readHello(vector.getString("hello").toByteArray(),vector.getLong("now"))
        assertEquals(vector.getString("senderId"),hello.id)
        val senderPrivate=privateKey(vector.getString("senderPrivateTestOnly"))
        val recipientPrivate=privateKey(vector.getString("recipientPrivateTestOnly"))
        val sender=KeyPair(KeyFactory.getInstance("RSA").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(hello.key))),senderPrivate)
        val recipientPublic=KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(
            privateKey(vector.getString("recipientPrivateTestOnly")).let {
                val spec=KeyFactory.getInstance("RSA").getKeySpec(it,java.security.spec.RSAPrivateCrtKeySpec::class.java)
                KeyFactory.getInstance("RSA").generatePublic(java.security.spec.RSAPublicKeySpec(spec.modulus,spec.publicExponent)).encoded
            }))
        val body=Protocol.open(vector.getString("textFrame").toByteArray(),KeyPair(recipientPublic,recipientPrivate),sender.public)
        assertEquals("TEXT",body.getString("type"));assertEquals(vector.getString("messageId"),body.getString("id"))
        assertEquals("Android ↔ Windows 日本語",body.getString("body"))
    }
}

