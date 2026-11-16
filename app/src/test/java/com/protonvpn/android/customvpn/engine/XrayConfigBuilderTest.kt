/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.engine

import android.util.Base64
import com.protonvpn.android.customvpn.model.CustomVpnProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class XrayConfigBuilderTest {

    @Test
    fun vlessWebSocketTlsBuildsExpectedOutboundAndTunInbound() {
        val profile = profile(
            protocol = "vless",
            rawUri = "vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&type=ws&security=tls&sni=cdn.example.com&host=cdn.example.com&path=%2Fedge#test"
        )

        val config = JSONObject(XrayConfigBuilder.build(profile))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")

        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("11111111-1111-4111-8111-111111111111",
            outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals("ws", stream.getString("network"))
        assertEquals("/edge", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
    }

    @Test
    fun vlessRealityRequiresPublicKeyAndEmitsRealitySettings() {
        val profile = profile(
            protocol = "vless",
            rawUri = "vless://11111111-1111-4111-8111-111111111111@example.com:443?type=tcp&security=reality&sni=www.example.com&fp=chrome&pbk=public-key&sid=abcd&spx=%2F"
        )

        val stream = JSONObject(XrayConfigBuilder.build(profile))
            .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        val reality = stream.getJSONObject("realitySettings")

        assertEquals("reality", stream.getString("security"))
        assertEquals("public-key", reality.getString("publicKey"))
        assertEquals("abcd", reality.getString("shortId"))
        assertEquals("chrome", reality.getString("fingerprint"))
    }

    @Test
    fun vmessBase64ProfileBuildsVmessOutbound() {
        val vmessJson = """{"add":"vmess.example.com","port":"8443","id":"22222222-2222-4222-8222-222222222222","aid":"0","scy":"auto","net":"tcp","tls":"tls","sni":"vmess.example.com"}"""
        val encoded = Base64.encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val profile = profile("vmess", "vmess://$encoded")

        val outbound = JSONObject(XrayConfigBuilder.build(profile))
            .getJSONArray("outbounds").getJSONObject(0)

        assertEquals("vmess", outbound.getString("protocol"))
        assertEquals("vmess.example.com", outbound.getJSONObject("settings")
            .getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals("tls", outbound.getJSONObject("streamSettings").getString("security"))
    }

    @Test
    fun unsupportedTransportFailsInsteadOfSilentlyDowngrading() {
        val profile = profile(
            protocol = "vless",
            rawUri = "vless://11111111-1111-4111-8111-111111111111@example.com:443?type=unsupported&security=none"
        )
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigBuilder.build(profile)
        }
    }

    @Test
    fun realityWithoutPublicKeyIsRejected() {
        val profile = profile(
            protocol = "vless",
            rawUri = "vless://11111111-1111-4111-8111-111111111111@example.com:443?type=tcp&security=reality&sni=www.example.com"
        )
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigBuilder.build(profile)
        }
    }


    @Test
    fun trojanDefaultsToTlsWhenShareLinkOmitsSecurityParameter() {
        val profile = profile(
            protocol = "trojan",
            rawUri = "trojan://secret-password@example.com:443?sni=example.com"
        )
        val outbound = JSONObject(XrayConfigBuilder.build(profile))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", outbound.getString("protocol"))
        assertEquals("tls", outbound.getJSONObject("streamSettings").getString("security"))
        assertEquals("example.com", outbound.getJSONObject("streamSettings")
            .getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun shadowsocksOutboundDoesNotIncludeTransportStreamSettings() {
        val profile = profile(
            protocol = "ss",
            rawUri = "ss://YWVzLTI1Ni1nY206cGFzcw==@example.com:8388#test"
        )
        val outbound = JSONObject(XrayConfigBuilder.build(profile))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", outbound.getString("protocol"))
        assertEquals("aes-256-gcm", outbound.getJSONObject("settings")
            .getJSONArray("servers").getJSONObject(0).getString("method"))
        assertFalse(outbound.has("streamSettings"))
    }


    @Test
    fun shadowsocksLegacyWholeAuthorityBase64IsSupported() {
        val credentials = "aes-256-gcm:secret@example.com:8388"
        val encoded = Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val profile = profile("ss", "ss://$encoded")
        val outbound = JSONObject(XrayConfigBuilder.build(profile))
            .getJSONArray("outbounds").getJSONObject(0)
        val server = outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("aes-256-gcm", server.getString("method"))
        assertEquals("secret", server.getString("password"))
    }

    private fun profile(protocol: String, rawUri: String) = CustomVpnProfile(
        id = "test",
        name = "test profile",
        protocol = protocol,
        server = if (protocol == "vmess") "vmess.example.com" else "example.com",
        port = if (protocol == "vmess") 8443 else 443,
        rawUri = rawUri,
    )
}
