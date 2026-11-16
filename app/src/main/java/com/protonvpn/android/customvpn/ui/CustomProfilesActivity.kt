/*
 * Copyright (c) 2026 Mahmood Barani
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.protonvpn.android.customvpn.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.protonvpn.android.base.ui.theme.VpnTheme
import com.protonvpn.android.customvpn.engine.CustomXrayVpnService
import com.protonvpn.android.customvpn.importer.ProxyProfileImporter
import com.protonvpn.android.customvpn.model.CustomVpnProfile
import com.protonvpn.android.customvpn.storage.CustomVpnProfileStore
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight profile manager for user-provided V2Ray/Xray share links and subscriptions.
 * Importing is local; remote subscriptions are fetched only after an explicit user action.
 */
class CustomProfilesActivity : ComponentActivity() {
    private lateinit var profileStore: CustomVpnProfileStore
    private var pendingConnectProfileId: String? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val profileId = pendingConnectProfileId
            pendingConnectProfileId = null
            if (result.resultCode == Activity.RESULT_OK && profileId != null) {
                CustomXrayVpnService.start(this, profileId)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = CustomVpnProfileStore(this)
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
        setContent {
            VpnTheme {
                CustomProfilesScreen(
                    initialText = sharedText,
                    loadProfiles = profileStore::getAll,
                    importProfiles = ::importProfiles,
                    refreshSubscriptions = ::refreshSubscriptions,
                    connect = ::connectProfile,
                    disconnect = { CustomXrayVpnService.stop(this) },
                    delete = { id ->
                        profileStore.remove(id)
                        profileStore.getAll()
                    },
                    pasteClipboard = ::readClipboard,
                    decodeQrImage = ::decodeQrImage,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun importProfiles(input: String, onResult: (Result<List<CustomVpnProfile>>) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val trimmed = input.trim()
                    val content = if (trimmed.startsWith("https://", true) ||
                        trimmed.startsWith("http://", true)
                    ) {
                        fetchSubscription(trimmed)
                    } else {
                        trimmed
                    }
                    ProxyProfileImporter.importText(
                        content,
                        source = trimmed.takeIf {
                            it.startsWith("https://", true) || it.startsWith("http://", true)
                        }
                    )
                }
            }
            result.onSuccess { profileStore.addAll(it) }
            onResult(result.map { profileStore.getAll() })
        }
    }

    private fun refreshSubscriptions(onResult: (Result<List<CustomVpnProfile>>) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sources = profileStore.getAll().mapNotNull { it.source }.distinct()
                    check(sources.isNotEmpty()) { "سابسکریپشن ذخیره‌شده‌ای برای به‌روزرسانی وجود ندارد" }
                    sources.forEach { source ->
                        val imported = ProxyProfileImporter.importText(fetchSubscription(source), source)
                        profileStore.replaceSource(source, imported)
                    }
                    profileStore.getAll()
                }
            }
            onResult(result)
        }
    }

    private fun fetchSubscription(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/plain, */*")
        return try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "دریافت سابسکریپشن ناموفق بود (HTTP ${connection.responseCode})"
            }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_SUBSCRIPTION_BYTES) { "حجم سابسکریپشن بیش از حد مجاز است" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun connectProfile(profileId: String): Boolean {
        // Android permits only one active VPN at a time. Avoid silently replacing the
        // Proton tunnel while its connection UI still believes it is connected.
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val activeVpn = connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } == true
        if (activeVpn) return false

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            CustomXrayVpnService.start(this, profileId)
        } else {
            pendingConnectProfileId = profileId
            vpnPermissionLauncher.launch(permissionIntent)
        }
        return true
    }

    private suspend fun decodeQrImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "تصویر QR قابل خواندن نیست" }
            BitmapFactory.decodeStream(stream)
        } ?: error("فایل تصویر نامعتبر است")
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private fun readClipboard(): String {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }

    companion object {
        private const val MAX_SUBSCRIPTION_BYTES = 1_048_576
    }
}

@Composable
private fun CustomProfilesScreen(
    initialText: String,
    loadProfiles: () -> List<CustomVpnProfile>,
    importProfiles: (String, (Result<List<CustomVpnProfile>>) -> Unit) -> Unit,
    refreshSubscriptions: ((Result<List<CustomVpnProfile>>) -> Unit) -> Unit,
    connect: (String) -> Boolean,
    disconnect: () -> Unit,
    delete: (String) -> List<CustomVpnProfile>,
    pasteClipboard: () -> String,
    decodeQrImage: suspend (Uri) -> String,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { decodeQrImage(uri) }
                    .onSuccess {
                        input = it
                        message = "متن QR خوانده شد؛ برای ذخیره، دکمه وارد کردن را بزنید."
                    }
                    .onFailure {
                        message = it.message ?: "خواندن QR ناموفق بود."
                    }
            }
        }
    }
    var input by remember { mutableStateOf(initialText) }
    var profiles by remember { mutableStateOf(loadProfiles()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("پروفایل‌های سفارشی", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("مدیریت کانفیگ‌های V2Ray و Xray", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onClose) { Text("بستن") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("افزودن کانفیگ یا سابسکریپشن", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    placeholder = { Text("لینک vless:// یا vmess:// یا آدرس سابسکریپشن") },
                    supportingText = { Text("می‌توانید چند لینک را در خط‌های جداگانه وارد کنید.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { input = pasteClipboard() }) { Text("چسباندن") }
                    OutlinedButton(onClick = { qrPicker.launch("image/*") }) { Text("QR تصویر") }
                    Button(
                        enabled = input.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            message = null
                            importProfiles(input) { result ->
                                busy = false
                                result.onSuccess {
                                    profiles = it
                                    input = ""
                                    message = "کانفیگ‌ها ذخیره شدند."
                                }.onFailure {
                                    message = it.message ?: "واردکردن کانفیگ ناموفق بود."
                                }
                            }
                        },
                    ) { Text(if (busy) "در حال دریافت..." else "وارد کردن") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("کانفیگ‌های ذخیره‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !busy && profiles.any { it.source != null },
                    onClick = {
                        busy = true
                        refreshSubscriptions { result ->
                            busy = false
                            result.onSuccess {
                                profiles = it
                                message = "سابسکریپشن‌ها به‌روزرسانی شدند."
                            }.onFailure {
                                message = it.message ?: "به‌روزرسانی ناموفق بود."
                            }
                        }
                    },
                ) { Text("به‌روزرسانی") }
                TextButton(onClick = disconnect) { Text("قطع اتصال") }
            }
        }

        if (profiles.isEmpty()) {
            Text("هنوز کانفیگی اضافه نشده است.", style = MaterialTheme.typography.bodyMedium)
        } else {
            profiles.forEach { profile ->
                ProfileCard(
                    profile = profile,
                    onConnect = {
                        message = if (connect(profile.id)) {
                            "درخواست اتصال ارسال شد؛ وضعیت را از اعلان VPN بررسی کنید."
                        } else {
                            "ابتدا اتصال VPN فعلی را از صفحه اصلی قطع کنید."
                        }
                    },
                    onDelete = { profiles = delete(profile.id) },
                )
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.height(12.dp))
        Text(
            "نکته: برای اتصال، ابتدا مجوز VPN اندروید را تأیید کنید. سابسکریپشن فقط با فشردن دکمه وارد کردن دریافت می‌شود.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileCard(
    profile: CustomVpnProfile,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${profile.protocol.uppercase()}  •  ${profile.server}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text("اتصال") }
                OutlinedButton(onClick = onDelete) { Text("حذف") }
            }
        }
    }
}
