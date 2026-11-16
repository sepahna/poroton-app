/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.engine

import android.util.Base64
import com.protonvpn.android.customvpn.model.CustomVpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Converts imported share links to a minimal Xray-core JSON document.
 *
 * Supported here: VLESS, VMess, Trojan, Shadowsocks, SOCKS/SOCKS5 and HTTP.
 * Only the transports and security modes represented by the source link are emitted.
 * Unknown transport/security values fail closed instead of silently changing semantics.
 */
object XrayConfigBuilder {
    fun build(profile: CustomVpnProfile, mtu: Int = 1500): String {
        require(mtu in 576..9000) { "MTU is out of range" }
        val outbound = when (profile.protocol.lowercase()) {
            "vless" -> buildVless(profile)
            "vmess" -> buildVmess(profile)
            "trojan" -> buildTrojan(profile)
            "ss" -> buildShadowsocks(profile)
            "socks", "socks5" -> buildSocks(profile)
            "http" -> buildHttp(profile)
            else -> error("Unsupported Xray protocol: ${profile.protocol}")
        }
        val tun = JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", mtu).put("userLevel", 8))
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls").put("quic")))
        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
        val block = JSONObject().put("tag", "block").put("protocol", "blackhole")
        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", JSONArray().put(
                JSONObject()
                    .put("type", "field")
                    .put("inboundTag", JSONArray().put("tun-in"))
                    .put("outboundTag", "proxy")
            ))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", JSONArray().put(outbound).put(direct).put(block))
            .put("routing", routing)
            .toString()
    }

    private fun buildVless(profile: CustomVpnProfile): JSONObject {
        val uri = shareUri(profile.rawUri)
        val userId = decode(uri.rawUserInfo.orEmpty())
        require(userId.isNotBlank()) { "VLESS user ID is missing" }
        val query = query(uri.rawQuery)
        val user = JSONObject().put("id", userId).put("encryption", query["encryption"] ?: "none")
        query["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val settings = JSONObject().put("vnext", JSONArray().put(
            JSONObject().put("address", profile.server).put("port", profile.port)
                .put("users", JSONArray().put(user))
        ))
        return outbound("vless", settings, streamSettings(query))
    }

    private fun buildVmess(profile: CustomVpnProfile): JSONObject {
        val encoded = profile.rawUri.substringAfter("://").substringBefore('#')
        val decoded = String(Base64.decode(padBase64(encoded), Base64.DEFAULT), StandardCharsets.UTF_8)
        val vmess = JSONObject(decoded)
        val user = JSONObject()
            .put("id", vmess.optString("id"))
            .put("alterId", vmess.optInt("aid", 0))
            .put("security", vmess.optString("scy").ifBlank { "auto" })
        require(user.optString("id").isNotBlank()) { "VMess user ID is missing" }
        val settings = JSONObject().put("vnext", JSONArray().put(
            JSONObject().put("address", profile.server).put("port", profile.port)
                .put("users", JSONArray().put(user))
        ))
        val query = linkedMapOf<String, String>()
        query["type"] = vmess.optString("net").ifBlank { "tcp" }
        query["security"] = vmess.optString("tls").ifBlank { "none" }
        query["sni"] = vmess.optString("sni").ifBlank { vmess.optString("host") }
        query["fp"] = vmess.optString("fp")
        query["alpn"] = vmess.optString("alpn")
        query["host"] = vmess.optString("host")
        query["path"] = vmess.optString("path")
        query["headerType"] = vmess.optString("type")
        query["serviceName"] = vmess.optString("path")
        return outbound("vmess", settings, streamSettings(query))
    }

    private fun buildTrojan(profile: CustomVpnProfile): JSONObject {
        val uri = shareUri(profile.rawUri)
        val password = decode(uri.rawUserInfo.orEmpty())
        require(password.isNotBlank()) { "Trojan password is missing" }
        val settings = JSONObject().put("servers", JSONArray().put(
            JSONObject().put("address", profile.server).put("port", profile.port).put("password", password)
        ))
        val transport = query(uri.rawQuery).toMutableMap()
        // Trojan is TLS by definition; many share links omit an explicit security parameter.
        transport.putIfAbsent("security", "tls")
        return outbound("trojan", settings, streamSettings(transport))
    }

    private fun buildShadowsocks(profile: CustomVpnProfile): JSONObject {
        val uri = shareUri(profile.rawUri)
        val authority = uri.rawAuthority.orEmpty()
        val userInfo = authority.substringBeforeLast('@', "")
        val decodedUser = decodeBase64IfPossible(userInfo).ifBlank { decode(userInfo) }
        val credentials = decodedUser.split(":", limit = 2)
        require(credentials.size == 2 && credentials[0].isNotBlank()) { "Shadowsocks method/password is invalid" }
        val settings = JSONObject().put("servers", JSONArray().put(
            JSONObject().put("address", profile.server).put("port", profile.port)
                .put("method", credentials[0]).put("password", credentials[1])
        ))
        return outbound("shadowsocks", settings)
    }

    private fun buildSocks(profile: CustomVpnProfile): JSONObject {
        val uri = shareUri(profile.rawUri)
        val server = JSONObject().put("address", profile.server).put("port", profile.port)
        uri.rawUserInfo?.let { raw ->
            val parts = decode(raw).split(":", limit = 2)
            if (parts.size == 2) {
                server.put("users", JSONArray().put(JSONObject().put("user", parts[0]).put("pass", parts[1])))
            }
        }
        val settings = JSONObject().put("servers", JSONArray().put(server))
        return outbound("socks", settings)
    }

    private fun buildHttp(profile: CustomVpnProfile): JSONObject {
        val uri = shareUri(profile.rawUri)
        val server = JSONObject().put("address", profile.server).put("port", profile.port)
        uri.rawUserInfo?.let { raw ->
            val parts = decode(raw).split(":", limit = 2)
            if (parts.size == 2) {
                server.put("users", JSONArray().put(JSONObject().put("user", parts[0]).put("pass", parts[1])))
            }
        }
        return outbound("http", JSONObject().put("servers", JSONArray().put(server)))
    }

    private fun streamSettings(query: Map<String, String>): JSONObject {
        val network = (query["type"] ?: "tcp").lowercase()
        require(network in setOf("tcp", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp")) {
            "Unsupported transport: $network"
        }
        val security = (query["security"] ?: "none").lowercase()
        require(security in setOf("none", "tls", "reality")) { "Unsupported security: $security" }
        val stream = JSONObject().put("network", network).put("security", security)

        when (network) {
            "h2", "http" -> {
                stream.put("network", "http")
                stream.put("httpSettings", JSONObject()
                    .put("path", query["path"] ?: "/")
                    .put("host", JSONArray().apply {
                        query["host"]?.split(',')?.forEach { put(it.trim()) }
                    }))
            }
            "ws" -> {
                val headers = JSONObject()
                query["host"]?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                stream.put("wsSettings", JSONObject()
                    .put("path", query["path"] ?: "/")
                    .put("headers", headers))
            }
            "grpc" -> {
                val grpc = JSONObject()
                    .put("serviceName", query["serviceName"] ?: query["path"] ?: "")
                    .put("multiMode", query["mode"] == "multi")
                query["authority"]?.takeIf { it.isNotBlank() }?.let { grpc.put("authority", it) }
                stream.put("grpcSettings", grpc)
            }
            "httpupgrade" -> {
                val headers = JSONObject()
                query["host"]?.takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                stream.put("httpupgradeSettings", JSONObject()
                    .put("path", query["path"] ?: "/")
                    .put("host", query["host"] ?: "")
                    .put("headers", headers))
            }
            "xhttp" -> {
                stream.put("xhttpSettings", JSONObject()
                    .put("path", query["path"] ?: "/")
                    .put("host", query["host"] ?: "")
                    .put("mode", query["mode"] ?: "auto"))
            }
            "tcp" -> {
                if (query["headerType"] == "http") {
                    val headers = JSONObject()
                    query["host"]?.takeIf { it.isNotBlank() }?.let { headers.put("Host", JSONArray().put(it)) }
                    stream.put("tcpSettings", JSONObject().put("header", JSONObject()
                        .put("type", "http")
                        .put("request", JSONObject().put("path", JSONArray().put(query["path"] ?: "/"))
                            .put("headers", headers))))
                }
            }
        }

        when (security) {
            "tls" -> {
                val tls = JSONObject().put("allowInsecure", query["allowInsecure"] == "1")
                query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("serverName", it) }
                query["fp"]?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                query["alpn"]?.takeIf { it.isNotBlank() }?.let { value ->
                    tls.put("alpn", JSONArray().apply { value.split(',').forEach { put(it.trim()) } })
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                val reality = JSONObject()
                query["sni"]?.takeIf { it.isNotBlank() }?.let { reality.put("serverName", it) }
                query["fp"]?.takeIf { it.isNotBlank() }?.let { reality.put("fingerprint", it) }
                query["pbk"]?.takeIf { it.isNotBlank() }?.let { reality.put("publicKey", it) }
                query["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("shortId", it) }
                query["spx"]?.takeIf { it.isNotBlank() }?.let { reality.put("spiderX", it) }
                require(reality.has("publicKey")) { "REALITY public key (pbk) is missing" }
                stream.put("realitySettings", reality)
            }
        }
        return stream
    }

    private fun outbound(protocol: String, settings: JSONObject, stream: JSONObject? = null): JSONObject {
        val result = JSONObject().put("tag", "proxy").put("protocol", protocol).put("settings", settings)
        if (stream != null) result.put("streamSettings", stream)
        return result
    }

    private fun shareUri(value: String) = java.net.URI(value)

    private fun query(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.mapNotNull { part ->
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            key.takeIf { it.isNotBlank() }?.let { it to value }
        }.toMap()

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodeBase64IfPossible(value: String): String =
        runCatching {
            String(Base64.decode(padBase64(value), Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
        }.getOrDefault("")

    private fun padBase64(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)
}
