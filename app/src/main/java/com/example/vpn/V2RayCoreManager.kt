package com.example.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.data.model.VpnServer
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object V2RayCoreManager {

    private var coreController: CoreController? = null
    private var isInitialized = false

    fun initEnv(context: Context) {
        if (isInitialized) return
        try {
            Seq.setContext(context.applicationContext)
            val v2rayDir = File(context.filesDir, "v2ray").apply { mkdirs() }

            // Copy bundled geo data assets if present in APK
            listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat").forEach { name ->
                val dest = File(v2rayDir, name)
                if (!dest.exists()) {
                    try {
                        context.assets.open(name).use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            val deviceId = UUID.randomUUID().toString().replace("-", "")
            Libv2ray.initCoreEnv(v2rayDir.absolutePath, deviceId)
            isInitialized = true
        } catch (_: Exception) {}
    }

    fun startCore(context: Context, server: VpnServer, pfd: ParcelFileDescriptor): Boolean {
        initEnv(context)
        stopCore()

        return try {
            val callback = object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(status: Long, msg: String?): Long = 0L
            }

            val controller = Libv2ray.newCoreController(callback)
            coreController = controller

            val configJson = buildV2RayConfig(server)
            val tunFd = pfd.fd

            controller.startLoop(configJson, tunFd)
            true
        } catch (_: Exception) {
            stopCore()
            false
        }
    }

    fun isRunning(): Boolean {
        return coreController?.isRunning ?: false
    }

    fun stopCore() {
        try {
            coreController?.stopLoop()
        } catch (_: Exception) {}
        coreController = null
    }

    fun queryTrafficStats(): Pair<Long, Long> {
        return try {
            val statsJson = coreController?.queryAllOutboundTrafficStats() ?: ""
            if (statsJson.isNotEmpty()) {
                val json = JSONObject(statsJson)
                val up = json.optLong("uplink", 0L)
                val down = json.optLong("downlink", 0L)
                Pair(down, up)
            } else {
                Pair(0L, 0L)
            }
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    fun buildV2RayConfig(server: VpnServer): String {
        val root = JSONObject()

        // 1. Policy & Stats
        root.put("stats", JSONObject())
        val policy = JSONObject().apply {
            val levels = JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                })
            }
            put("levels", levels)
            val system = JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
            put("system", system)
        }
        root.put("policy", policy)

        // 2. Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // 3. DNS Configuration
        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("https://1.1.1.1/dns-query")
                put("localhost")
            }
            put("servers", servers)
        })

        // 4. Inbounds: Official v2rayNG TUN inbound + SOCKS/HTTP local proxies
        val inbounds = JSONArray()

        // TUN Inbound (Standard v2rayNG xray0)
        inbounds.put(JSONObject().apply {
            put("tag", "tun")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", 1500)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                    put("quic")
                })
            })
        })

        // SOCKS5 Inbound
        inbounds.put(JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            })
        })

        // HTTP Inbound
        inbounds.put(JSONObject().apply {
            put("tag", "http")
            put("port", 10809)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject().apply {
                put("allowTransparent", false)
            })
        })
        root.put("inbounds", inbounds)

        // 5. Outbounds: Selected Server + Direct Fallback
        val outbounds = JSONArray()

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")

            val settings = JSONObject()
            val streamSettings = JSONObject()

            when (server.protocol) {
                "hysteria2", "hy2" -> {
                    put("protocol", "hysteria")
                    settings.put("version", 2)
                    settings.put("address", server.host)
                    settings.put("port", server.port)

                    streamSettings.put("network", "hysteria")
                    streamSettings.put("security", "tls")
                    streamSettings.put("tlsSettings", JSONObject().apply {
                        put("serverName", if (server.sni.isNotEmpty()) server.sni else server.host)
                        put("allowInsecure", true)
                        put("alpn", JSONArray().apply { put("h3") })
                    })
                    streamSettings.put("hysteriaSettings", JSONObject().apply {
                        put("version", 2)
                        put("auth", server.auth)
                    })
                }
                "vless" -> {
                    put("protocol", "vless")
                    val vnext = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", server.auth)
                                    put("encryption", "none")
                                    put("level", 8)
                                })
                            })
                        })
                    }
                    settings.put("vnext", vnext)
                    streamSettings.put("network", "tcp")
                    if (server.sni.isNotEmpty()) {
                        streamSettings.put("security", "tls")
                        streamSettings.put("tlsSettings", JSONObject().apply {
                            put("serverName", server.sni)
                            put("allowInsecure", true)
                        })
                    }
                }
                "vmess" -> {
                    put("protocol", "vmess")
                    val vnext = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", server.auth)
                                    put("alterId", 0)
                                    put("security", "auto")
                                    put("level", 8)
                                })
                            })
                        })
                    }
                    settings.put("vnext", vnext)
                    streamSettings.put("network", "tcp")
                }
                "trojan" -> {
                    put("protocol", "trojan")
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("password", server.auth)
                            put("level", 8)
                        })
                    }
                    settings.put("servers", serversArr)
                    streamSettings.put("network", "tcp")
                    streamSettings.put("security", "tls")
                    streamSettings.put("tlsSettings", JSONObject().apply {
                        put("serverName", if (server.sni.isNotEmpty()) server.sni else server.host)
                        put("allowInsecure", true)
                    })
                }
                "shadowsocks", "ss" -> {
                    put("protocol", "shadowsocks")
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("method", "aes-256-gcm")
                            put("password", server.auth)
                            put("level", 8)
                        })
                    }
                    settings.put("servers", serversArr)
                    streamSettings.put("network", "tcp")
                }
                else -> {
                    put("protocol", "socks")
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                        })
                    }
                    settings.put("servers", serversArr)
                }
            }

            put("settings", settings)
            put("streamSettings", streamSettings)
        }
        outbounds.put(proxyOutbound)

        // Direct Outbound (freedom)
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
        })

        root.put("outbounds", outbounds)

        // 6. Routing: Route TUN traffic through proxy outbound
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray().apply {
                // Route DNS traffic
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("tun") })
                    put("port", "53")
                    put("outboundTag", "proxy")
                })
                // Route all IP traffic from TUN through proxy
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("tun") })
                    put("outboundTag", "proxy")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString()
    }
}
