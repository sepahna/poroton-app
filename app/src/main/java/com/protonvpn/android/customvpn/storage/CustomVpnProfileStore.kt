/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.storage

import android.content.Context
import com.protonvpn.android.customvpn.model.CustomVpnProfile
import org.json.JSONArray

/**
 * Small persistent store for imported profiles. This intentionally uses a dedicated
 * preference file and does not touch Proton account/profile data.
 */
class CustomVpnProfileStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("custom_vpn_profiles_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<CustomVpnProfile> {
        val raw = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                runCatching { CustomVpnProfile.fromJson(array.getJSONObject(index)) }
                    .getOrNull()?.let(::add)
            }
        }
    }

    @Synchronized
    fun addAll(profiles: List<CustomVpnProfile>): Int {
        val current = getAll().associateBy { it.rawUri }.toMutableMap()
        var added = 0
        profiles.forEach { profile ->
            if (profile.rawUri !in current) {
                current[profile.rawUri] = profile
                added++
            }
        }
        save(current.values.toList())
        return added
    }

    @Synchronized
    fun replaceSource(source: String, profiles: List<CustomVpnProfile>) {
        val retained = getAll().filterNot { it.source == source }
        save((retained + profiles.map { it.copy(source = source) }).distinctBy { it.rawUri })
    }

    @Synchronized
    fun remove(id: String) {
        save(getAll().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(profiles: List<CustomVpnProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        check(preferences.edit().putString(KEY_PROFILES, array.toString()).commit()) {
            "ذخیره کانفیگ‌ها انجام نشد"
        }
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
    }
}
