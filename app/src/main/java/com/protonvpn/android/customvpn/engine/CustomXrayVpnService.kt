/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.protonvpn.android.customvpn.storage.CustomVpnProfileStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VPN service for user-imported Xray profiles.
 *
 * The service owns the TUN descriptor for the entire native core lifetime. The app package is
 * excluded from its own tunnel so Xray's outbound sockets cannot recursively enter this TUN.
 */
@AndroidEntryPoint
class CustomXrayVpnService : VpnService() {
    @Inject lateinit var xrayRuntime: XrayCoreRuntime

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var startJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی اتصال"))
                startTunnel(profileId, startId)
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        startJob?.cancel()
        xrayRuntime.stop()
        closeTun()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTunnel(profileId: String, startId: Int) {
        startJob?.cancel()
        startJob = serviceScope.launch {
            try {
                check(VpnService.prepare(this@CustomXrayVpnService) == null) {
                    "مجوز VPN داده نشده است"
                }
                val profile = CustomVpnProfileStore(this@CustomXrayVpnService)
                    .getAll().firstOrNull { it.id == profileId }
                    ?: error("پروفایل انتخاب‌شده پیدا نشد")

                val descriptor = Builder()
                    .setSession(profile.name)
                    .setMtu(DEFAULT_MTU)
                    .addAddress(TUN_ADDRESS, 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(DNS_SERVER)
                    .addDisallowedApplication(packageName)
                    .establish() ?: error("ساخت رابط VPN ناموفق بود")

                tunInterface = descriptor
                val config = XrayConfigBuilder.build(profile, DEFAULT_MTU)
                xrayRuntime.start(config, descriptor.fd)
                updateNotification("متصل: ${profile.name}")
            } catch (error: Exception) {
                xrayRuntime.stop()
                closeTun()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun stopTunnel() {
        startJob?.cancel()
        serviceScope.launch {
            xrayRuntime.stop()
            closeTun()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun closeTun() {
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "اتصال VPN سفارشی",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val disconnectIntent = Intent(this, CustomXrayVpnService::class.java).setAction(ACTION_DISCONNECT)
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            0,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VPN سفارشی")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "قطع اتصال", disconnectPendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    companion object {
        const val ACTION_CONNECT = "com.protonvpn.android.customvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.protonvpn.android.customvpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "custom_profile_id"

        private const val CHANNEL_ID = "custom_xray_vpn"
        private const val NOTIFICATION_ID = 7821
        private const val DEFAULT_MTU = 1500
        private const val TUN_ADDRESS = "10.111.0.1"
        private const val DNS_SERVER = "1.1.1.1"

        /** Call only after the Activity has completed VpnService.prepare() consent. */
        fun start(context: android.content.Context, profileId: String) {
            check(VpnService.prepare(context) == null) { "VPN permission is required first" }
            val intent = Intent(context, CustomXrayVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, CustomXrayVpnService::class.java).setAction(ACTION_DISCONNECT)
            )
        }
    }
}
