package com.example

import com.example.data.parser.V2RayConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testParseExampleServerConfig() {
        val config = "hysteria2://96ab5cd282733fd4c312a61f972f330a@chicken.banana:443?alpn=h3&sni=chicken.banana#Example Server 67"
        val server = V2RayConfigParser.parseSingleConfig(config)
        assertNotNull(server)
        assertEquals("Example Server 67", server?.name)
        assertEquals("chicken.banana", server?.host)
        assertEquals(443, server?.port)
        assertEquals("hysteria2", server?.protocol)
        assertEquals("96ab5cd282733fd4c312a61f972f330a", server?.auth)
        assertEquals("chicken.banana", server?.sni)
        assertEquals("h3", server?.alpn)
    }

    @Test
    fun testParseConcatenatedConfigsFromGitHub() {
        // Real-world content from the GitHub repository where configs are pasted together without newlines
        val rawGitHub = "hysteria2://96ab5cd282733fd4c312a61f972f330a@vp3.yysyy.online:443?alpn=h3&sni=vp3.yysyy.online#%F0%9F%87%AF%F0%9F%87%B5%20Japanhysteria2://96ab5cd282733fd4c312a61f972f330a@45.32.10.7:443?alpn=h3&sni=vp3.yysyy.online#%F0%9F%87%AF%F0%9F%87%B5%20Japan"
        val servers = V2RayConfigParser.parseConfigList(rawGitHub)
        assertEquals(2, servers.size)
        assertEquals("🇯🇵 Japan", servers[0].name)
        assertEquals("vp3.yysyy.online", servers[0].host)
        assertEquals("96ab5cd282733fd4c312a61f972f330a", servers[0].auth)
        assertEquals("vp3.yysyy.online", servers[0].sni)
        assertEquals("h3", servers[0].alpn)

        assertEquals("🇯🇵 Japan", servers[1].name)
        assertEquals("45.32.10.7", servers[1].host)
        assertEquals("96ab5cd282733fd4c312a61f972f330a", servers[1].auth)
    }
}
