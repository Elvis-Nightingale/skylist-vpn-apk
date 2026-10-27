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

            // Copy bundled geo data assets if present
            listOf("geoip.dat", "geosite.dat").forEach { name ->
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
        } catch (e: Exception) {
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

    fun buildV2RayConfig(server: VpnServer): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // DNS
        root.put("dns", JSONObject().apply {
            val servers = JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("localhost")
            }
            put("servers", servers)
        })

        // Inbounds: SOCKS5 & HTTP
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                })
            })
        })
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

        // Outbounds
        val outbounds = JSONArray()

        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", server.protocol)

            val settings = JSONObject()
            val streamSettings = JSONObject()

            when (server.protocol) {
                "hysteria2" -> {
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("password", server.auth)
                        })
                    }
                    settings.put("servers", serversArr)

                    streamSettings.put("network", "udp")
                    streamSettings.put("security", "tls")
                    streamSettings.put("tlsSettings", JSONObject().apply {
                        put("serverName", if (server.sni.isNotEmpty()) server.sni else server.host)
                        put("allowInsecure", true)
                        put("alpn", JSONArray().apply { put(if (server.alpn.isNotEmpty()) server.alpn else "h3") })
                    })
                }
                "vless" -> {
                    val vnext = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", server.auth)
                                    put("encryption", "none")
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
                    val vnext = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", server.auth)
                                    put("alterId", 0)
                                    put("security", "auto")
                                })
                            })
                        })
                    }
                    settings.put("vnext", vnext)
                    streamSettings.put("network", "tcp")
                }
                "trojan" -> {
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("password", server.auth)
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
                    val serversArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.host)
                            put("port", server.port)
                            put("method", "aes-256-gcm")
                            put("password", server.auth)
                        })
                    }
                    settings.put("servers", serversArr)
                    streamSettings.put("network", "tcp")
                }
                else -> {
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

        // Direct fallback
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })

        root.put("outbounds", outbounds)

        // Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("network", "tcp,udp")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString()
    }
}
