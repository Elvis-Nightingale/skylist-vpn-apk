package com.example.data.parser

import android.util.Base64
import com.example.data.model.VpnServer
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

object V2RayConfigParser {

    private val CONFIG_REGEX = Regex(
        """((?:hysteria2|vless|vmess|trojan|ss|hy2|hysteria)://.+?)(?=(?:hysteria2|vless|vmess|trojan|ss|hy2|hysteria)://|$)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parseConfigList(rawText: String): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        if (rawText.isBlank()) return servers

        // Match configs even if concatenated without newlines (e.g. Japanhysteria2://) or separated by spaces
        val matches = CONFIG_REGEX.findAll(rawText).map { it.groupValues[1].trim() }.toList()

        val candidateStrings = if (matches.isNotEmpty()) {
            matches
        } else {
            rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        var index = 1
        for (candidate in candidateStrings) {
            val line = candidate.trim()
            if (line.isEmpty() || line.startsWith("//") || (line.startsWith("#") && !line.contains("://"))) {
                continue
            }

            try {
                val server = parseSingleConfig(line, index)
                if (server != null) {
                    servers.add(server)
                    index++
                }
            } catch (_: Exception) {
                // skip malformed line gracefully
            }
        }

        return servers
    }

    fun parseSingleConfig(rawLine: String, index: Int = 1): VpnServer? {
        val line = rawLine.trim()
        val schemeIdx = line.indexOf("://")
        if (schemeIdx == -1) return null

        val protocol = line.substring(0, schemeIdx).lowercase().trim()
        val rest = line.substring(schemeIdx + 3)

        val hashIdx = rest.indexOf('#')
        val fragment = if (hashIdx != -1) rest.substring(hashIdx + 1).trim() else ""
        val decodedFragmentName = if (fragment.isNotEmpty()) {
            try {
                URLDecoder.decode(fragment, "UTF-8")
            } catch (_: Exception) {
                fragment
            }
        } else ""

        val mainPartWithQuery = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
        val queryIdx = mainPartWithQuery.indexOf('?')
        val authority = if (queryIdx != -1) mainPartWithQuery.substring(0, queryIdx) else mainPartWithQuery

        var host = ""
        var port = 443
        var extractedName = decodedFragmentName

        if (protocol == "vmess" && !authority.contains("@") && !authority.contains(":")) {
            // Likely base64 encoded VMess JSON
            try {
                val decodedJson = String(Base64.decode(authority, Base64.DEFAULT), Charsets.UTF_8)
                val json = JSONObject(decodedJson)
                host = json.optString("add", "")
                port = json.optInt("port", 443)
                if (extractedName.isEmpty()) {
                    extractedName = json.optString("ps", "")
                }
            } catch (_: Exception) {
                // fallback
            }
        }

        if (host.isEmpty()) {
            val target = if (authority.contains("@")) authority.substringAfterLast('@') else authority
            if (target.contains(":")) {
                val lastColon = target.lastIndexOf(':')
                host = target.substring(0, lastColon).trim('[', ']')
                port = target.substring(lastColon + 1).toIntOrNull() ?: 443
            } else {
                host = target.trim('[', ']')
                port = 443
            }
        }

        if (host.isEmpty()) {
            return null
        }

        val finalName = when {
            extractedName.isNotEmpty() -> extractedName
            else -> "$protocol - $host"
        }

        // Unique ID including index so duplicate named servers (e.g. two "Japan" configs) have distinct keys
        val uniqueId = UUID.nameUUIDFromBytes("$protocol://$host:$port#$finalName-$index".toByteArray()).toString()

        return VpnServer(
            id = uniqueId,
            name = finalName,
            rawConfig = line,
            protocol = protocol,
            host = host,
            port = port
        )
    }
}
