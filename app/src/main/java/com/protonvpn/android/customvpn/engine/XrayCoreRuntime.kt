/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.engine

import android.content.Context
import xraygo.Seq
import dagger.hilt.android.qualifiers.ApplicationContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small lifecycle-safe adapter around the pinned AndroidLibXrayLite runtime.
 *
 * Calls to [start] and [stop] are blocking native operations and must be made from a
 * background/service dispatcher. This class deliberately does not create a TUN interface:
 * the Android VpnService must own that descriptor and pass its fd to [start].
 *
 * Never log the supplied config because it contains proxy credentials.
 */
@Singleton
class XrayCoreRuntime @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var controller: CoreController? = null

    /** Returns whether the native core currently reports itself as running. */
    fun isRunning(): Boolean = synchronized(lock) {
        controller?.isRunning == true
    }

    /**
     * Starts Xray using a complete Xray JSON document and an already-established TUN fd.
     * Pass 0 only for proxy-only mode; a VPN service should pass its live ParcelFileDescriptor.fd.
     */
    fun start(configJson: String, tunFd: Int): Boolean = synchronized(lock) {
        require(configJson.isNotBlank()) { "Xray config must not be empty" }
        require(tunFd >= 0) { "TUN file descriptor must be non-negative" }

        val core = controller ?: createController().also { controller = it }
        if (core.isRunning) return false

        core.startLoop(configJson, tunFd)
        check(core.isRunning) { "Xray core did not enter the running state" }
        true
    }

    /** Stops the native core. Safe to call repeatedly. */
    fun stop() = synchronized(lock) {
        val core = controller ?: return
        if (core.isRunning) {
            core.stopLoop()
        }
    }

    private fun createController(): CoreController {
        val assetDirectory = File(appContext.filesDir, "xray-assets")
        check(assetDirectory.exists() || assetDirectory.mkdirs()) {
            "Unable to create Xray asset directory"
        }

        Seq.setContext(appContext)
        Libv2ray.initCoreEnv(assetDirectory.absolutePath, UUID.randomUUID().toString())

        val callback = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L

            // Do not forward native status text to analytics or logs: messages may include
            // endpoint details. The owning service can expose a sanitized connection state.
            override fun onEmitStatus(code: Long, message: String?): Long = 0L
        }
        return Libv2ray.newCoreController(callback)
    }
}
