/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class ProxyProfileImporterTest {
    @Test
    fun parsesVlessShareLink() {
        val profile = ProxyProfileImporter.parseShareLink(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none#NL"
        )
        assertEquals("vless", profile.protocol)
        assertEquals("example.com", profile.server)
        assertEquals(443, profile.port)
        assertEquals("NL", profile.name)
    }

    @Test
    fun parsesVmessJsonLink() {
        val payload = """{"add":"vmess.example","port":"8443","ps":"VMess NL"}"""
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val profile = ProxyProfileImporter.parseShareLink("vmess://$encoded")
        assertEquals("vmess", profile.protocol)
        assertEquals("vmess.example", profile.server)
        assertEquals(8443, profile.port)
    }

    @Test
    fun parsesUppercaseVmessScheme() {
        val payload = """{"add":"vmess.example","port":"8443","ps":"VMess NL"}"""
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val profile = ProxyProfileImporter.parseShareLink("VMESS://$encoded")
        assertEquals("vmess", profile.protocol)
        assertEquals("vmess.example", profile.server)
    }


    @Test
    fun parsesVmessShareLinkWithOptionalFragment() {
        val payload = """{"add":"vmess.example","port":"8443","id":"33333333-3333-4333-8333-333333333333","ps":"VMess NL"}"""
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        val profile = ProxyProfileImporter.parseShareLink("vmess://$encoded#fragment")
        assertEquals("vmess", profile.protocol)
        assertEquals("vmess.example", profile.server)
    }

    @Test
    fun importsBase64SubscriptionAndDeduplicates() {
        val link = "trojan://secret@example.com:443#one"
        val subscription = Base64.getEncoder().encodeToString("$link\n$link".toByteArray())
        val profiles = ProxyProfileImporter.importText(subscription)
        assertEquals(1, profiles.size)
        assertEquals("trojan", profiles.single().protocol)
    }

    @Test
    fun rejectsUnsupportedInput() {
        val result = runCatching { ProxyProfileImporter.importText("not a proxy link") }
        assertTrue(result.isFailure)
    }
}
