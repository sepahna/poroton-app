/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.model

import org.json.JSONObject

/**
 * Imported proxy profile metadata. Secrets are kept in [rawUri] and must never be
 * written to logs or analytics.
 */
data class CustomVpnProfile(
    val id: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val rawUri: String,
    val source: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol)
        .put("server", server)
        .put("port", port)
        .put("rawUri", rawUri)
        .put("source", source)

    companion object {
        fun fromJson(json: JSONObject) = CustomVpnProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            protocol = json.getString("protocol"),
            server = json.getString("server"),
            port = json.getInt("port"),
            rawUri = json.getString("rawUri"),
            source = json.optString("source").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}
