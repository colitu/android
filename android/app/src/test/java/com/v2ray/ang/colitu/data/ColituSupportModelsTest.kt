package com.v2ray.ang.colitu.data

import com.google.gson.JsonParser
import com.v2ray.ang.colitu.repository.ColituSupportRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColituSupportModelsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun conversationAndMessagesFollowThePanelContract() {
        val conversation = ColituSupportConversation.fromJson(
            json("""{"id":"c1","subject":"Yavaş","status":"resolved","unread":2,"last_message":"Tamam","last_message_at":"2026-09-25T10:15:00.123456Z"}"""),
        )!!
        assertEquals("resolved", conversation.status)
        assertEquals(2, conversation.unread)
        assertFalse(conversation.closed)
        assertEquals(1790331300L, conversation.lastMessageAt!!.epochSecond)

        val message = ColituSupportMessage.fromJson(
            json("""{"id":"m1","sender":"admin","admin_name":"deniz","body":"Merhaba","created_at":"2026-09-25T13:15:00+03:00","attachments":[{"id":"a1","file_name":"s.png","content_type":"image/png","size_bytes":2048,"is_image":true}]}"""),
        )!!
        assertFalse(message.mine)
        assertEquals("deniz", message.adminName)
        assertEquals(1790331300L, message.createdAt!!.epochSecond)
        assertTrue(message.attachments.single().isImage)
    }

    @Test
    fun serversCarryPanelCategories() {
        val server = ColituServer.fromJson(
            json("""{"id":"n1","name":"Estonya","country":"EE","status":"online","protocols":["vless-reality"],"categories":["Streaming","ai","ai"]}"""),
        )!!
        assertEquals(listOf("streaming", "ai"), server.categories)
    }

    @Test
    fun diagnosticsLogsHideCredentials() {
        val text = ColituSupportRepository.redact(
            "vless://0f5c2d9e-1111@pro.example.org:8443 Bearer eyJabc.def {\"password\":\"hunter2\",\"server\":\"x\"}",
        )
        assertFalse(text.contains("0f5c2d9e") || text.contains("eyJabc") || text.contains("hunter2"))
        assertTrue(text.contains("vless://***@") && text.contains("\"server\":\"x\""))
        // Node host names are masked too.
        assertFalse(text.contains("pro.example.org"))
        val hosts = ColituSupportRepository.redact(
            "{\"serverName\":\"sni.example.test\"} tls sni=sni2.example.test dial node.example.test:8443 https://mirror.example.test/capi https://api.colitu.com/api/v1",
        )
        for (leak in listOf("sni.example.test", "sni2.example.test", "node.example.test", "mirror.example.test")) {
            assertFalse("$leak leaked", hosts.contains(leak))
        }
        assertTrue(hosts.contains("https://api.colitu.com/api/v1"))
    }

    @Test
    fun diagnosticsLogsKeepTimesButHideIpv6() {
        val text = ColituSupportRepository.redact("10-15 05:15:16.412 I/Colitu dial 2a02:6b8::1 and 2001:db8:85a3:0:0:8a2e:370:7334")
        assertTrue(text, text.contains("10-15 05:15:16.412"))
        assertFalse(text, text.contains("2a02:6b8") || text.contains("2001:db8"))
    }
}
