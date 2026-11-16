/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.importer

import android.util.Base64
import com.protonvpn.android.customvpn.model.CustomVpnProfile
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Parses common share links and subscription payloads without starting a tunnel.
 *
 * Supported share-link schemes: vless, vmess, trojan, ss, socks, socks5 and http.
 * Xray runtime integration is deliberately separate from importing and persistence.
 */
object ProxyProfileImporter {
    private val supportedSchemes = setOf("vless", "vmess", "trojan", "ss", "socks", "socks5", "http")

    fun importText(input: String, source: String? = null): List<CustomVpnProfile> {
        val text = input.trim()
        require(text.isNotEmpty()) { "ورودی خالی است" }
        val decoded = decodeSubscription(text)
        val lines = decoded.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()
        val profiles = lines.mapNotNull { line ->
            runCatching { parseShareLink(line, source) }.getOrNull()
        }.distinctBy { it.rawUri }
        require(profiles.isNotEmpty()) { "هیچ کانفیگ پشتیبانی‌شده‌ای پیدا نشد" }
        return profiles
    }

    fun parseShareLink(link: String, source: String? = null): CustomVpnProfile {
        val value = link.trim()
        require(value.isNotEmpty()) { "لینک خالی است" }
        if (value.startsWith("vmess://", ignoreCase = true)) return parseVmess(value, source)

        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: error("پروتکل مشخص نیست")
        require(scheme in supportedSchemes) { "پروتکل پشتیبانی نمی‌شود: $scheme" }
        if (scheme == "ss") return parseShadowsocks(value, source)
        val authority = uri.rawAuthority ?: error("آدرس سرور نامعتبر است")
        val endpoint = authority.substringAfterLast('@')
        val hostPort = endpoint.substringBefore('?').substringBefore('#')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            require(end > 0) { "IPv6 نامعتبر است" }
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 2).toInt()
        } else {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toInt()
        }
        require(host.isNotBlank() && port in 1..65535) { "آدرس یا پورت نامعتبر است" }
        val fragment = uri.rawFragment?.let(::urlDecode)
        return CustomVpnProfile(
            id = UUID.randomUUID().toString(),
            name = fragment?.takeIf { it.isNotBlank() } ?: "$scheme • $host",
            protocol = scheme,
            server = host,
            port = port,
            rawUri = value,
            source = source,
        )
    }

    private fun parseVmess(link: String, source: String?): CustomVpnProfile {
        val encoded = link.removePrefix("vmess://")
        val jsonText = String(Base64.decode(padBase64(encoded), Base64.DEFAULT), StandardCharsets.UTF_8)
        val json = JSONObject(jsonText)
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
        require(host.isNotBlank() && port in 1..65535) { "کانفیگ VMess ناقص است" }
        return CustomVpnProfile(
            id = UUID.randomUUID().toString(),
            name = json.optString("ps").takeIf { it.isNotBlank() } ?: "VMess • $host",
            protocol = "vmess",
            server = host,
            port = port,
            rawUri = link,
            source = source,
        )
    }

    private fun parseShadowsocks(link: String, source: String?): CustomVpnProfile {
        val uri = URI(link)
        val raw = uri.rawSchemeSpecificPart.substringBefore('?')
        val decoded = runCatching {
            String(Base64.decode(padBase64(raw), Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
        }.getOrNull()
        val authority = decoded?.takeIf { it.contains('@') } ?: raw
        val endpoint = authority.substringAfterLast('@')
        val hostPort = endpoint.substringBefore('#')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            require(end > 0) { "IPv6 نامعتبر است" }
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 2).toInt()
        } else {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toInt()
        }
        require(host.isNotBlank() && port in 1..65535) { "آدرس یا پورت نامعتبر است" }
        return CustomVpnProfile(
            id = UUID.randomUUID().toString(),
            name = uri.rawFragment?.let(::urlDecode)?.takeIf { it.isNotBlank() } ?: "Shadowsocks • $host",
            protocol = "ss",
            server = host,
            port = port,
            rawUri = link,
            source = source,
        )
    }

    private fun decodeSubscription(text: String): String {
        if (text.lineSequence().any { line ->
                val candidate = line.trim()
                candidate.contains("://") && candidate.substringBefore("://").lowercase() in supportedSchemes
            }) return text
        val compact = text.filterNot { it.isWhitespace() }
        val decoded = runCatching {
            String(Base64.decode(padBase64(compact), Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrNull()
        return decoded?.takeIf { it.contains("://") } ?: text
    }

    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
