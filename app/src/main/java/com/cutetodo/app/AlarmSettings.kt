package com.cutetodo.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Status tiga hal yang menentukan pengingat bisa muncul & berbunyi saat aplikasi ditutup, masing-masing dengan pintasan ke pengaturan sistem. */
@Composable
fun NotificationSettingsDialog(th: CuteTheme, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val refresh = rememberResumeTick()
    val cs = MaterialTheme.colorScheme

    val notifOn = remember(refresh) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val batteryOk = remember(refresh) { batteryIgnored(ctx) }
    val fsOk: Boolean? = remember(refresh) {
        if (Build.VERSION.SDK_INT >= 34) ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else null
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pengaturan notifikasi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Aktifkan ketiganya supaya pengingat selalu muncul dan berbunyi, walau aplikasi ditutup.",
                    fontSize = 13.sp, color = cs.onSurfaceVariant
                )
                SettingRow(
                    Icons.Rounded.Notifications, "Notifikasi",
                    if (notifOn) "Aktif" else "Mati, pengingat tidak akan muncul",
                    ok = notifOn, action = if (notifOn) "Atur" else "Aktifkan", th = th
                ) { openNotifSettings(ctx) }
                SettingRow(
                    Icons.Rounded.BatteryChargingFull, "Hemat baterai",
                    if (batteryOk) "Tanpa batasan" else "Dibatasi sistem, pengingat bisa telat",
                    ok = batteryOk, action = "Ubah", th = th
                ) { openBattery(ctx) }
                SettingRow(
                    Icons.Rounded.PowerSettingsNew, "Autostart / latar belakang",
                    "Perlu di Xiaomi, Oppo, Vivo, Huawei, Samsung",
                    ok = null, action = "Buka", th = th
                ) { openAutostart(ctx) }
                SettingRow(
                    Icons.Rounded.Lock, "Tampil di layar kunci",
                    when (fsOk) {
                        true -> "Aktif: layar menyala saat pengingat bunyi"
                        false -> "Mati, hanya muncul sebagai notifikasi"
                        null -> "Xiaomi/Oppo/Vivo: izinkan \"Tampilkan di layar kunci\" dan \"Jendela pop-up\""
                    },
                    ok = fsOk, action = if (fsOk == true) "Atur" else "Aktifkan", th = th
                ) { openLockScreen(ctx) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Tutup") } }
    )
}

/** Naik setiap kali layar kembali aktif (mis. dari pengaturan sistem) supaya status izin disegarkan. */
@Composable
private fun rememberResumeTick(): Int {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = remember(ctx) { generateSequence<Context>(ctx) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<LifecycleOwner>().firstOrNull() }
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(ob)
        onDispose { owner?.lifecycle?.removeObserver(ob) }
    }
    return tick
}

internal fun batteryIgnored(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** Merek yang punya halaman Autostart sendiri (tidak ada API standar, hanya bisa dibuka lalu diaktifkan pengguna). */
internal fun needsAutostart(): Boolean =
    Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor")

internal fun needsBackgroundSetup(ctx: Context): Boolean = !batteryIgnored(ctx) || needsAutostart()

/**
 * Muncul saat pertama kali aplikasi dibuka dan WAJIB diselesaikan: tombol "Selesai" terkunci, dialog tidak bisa ditutup
 * (back / ketuk di luar). Android tidak mengizinkan aplikasi mengaktifkan ini sendiri, jadi pengguna diarahkan ke pengaturannya.
 *
 * - Baterai "tanpa batasan": diperiksa otomatis (bisa dicek lewat API).
 * - Autostart (Xiaomi, Oppo, Vivo, dst.): tidak ada API untuk memeriksanya, jadi pengguna harus membuka halamannya
 *   lebih dulu lalu mencentang bahwa sudah diaktifkan.
 */
@Composable
fun BackgroundSetupDialog(th: CuteTheme, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val refresh = rememberResumeTick()
    val batteryOk = remember(refresh) { batteryIgnored(ctx) }
    val autostart = needsAutostart()
    var autostartOpened by rememberSaveable { mutableStateOf(false) }
    var autostartDone by rememberSaveable { mutableStateOf(false) }
    val canFinish = batteryOk && (!autostart || autostartDone)

    AlertDialog(
        onDismissRequest = {},   // wajib diselesaikan
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Biar pengingat selalu bunyi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Supaya notifikasi dan suara tetap muncul walau aplikasi ditutup, aplikasi harus diizinkan berjalan di latar belakang. Wajib diaktifkan dulu sebelum lanjut. Cukup sekali.",
                    fontSize = 14.sp
                )
                if (batteryOk) Text("✓ Baterai: tanpa batasan", fontSize = 13.sp, color = th.primary)
                else Button(onClick = { openBattery(ctx) }) { Text("1. Baterai: tanpa batasan") }

                if (autostart) {
                    if (autostartOpened) OutlinedButton(onClick = { openAutostart(ctx) }) { Text("Buka Autostart lagi") }
                    else OutlinedButton(onClick = { openAutostart(ctx); autostartOpened = true }) { Text("2. Aktifkan Autostart") }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = autostartOpened) { autostartDone = !autostartDone },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = autostartDone, onCheckedChange = { autostartDone = it }, enabled = autostartOpened)
                        Text(
                            "Autostart sudah saya aktifkan",
                            fontSize = 13.sp,
                            color = if (autostartOpened) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }

                if (!canFinish) Text(
                    if (!batteryOk) "Ketuk tombol di atas dan pilih \"Izinkan\" agar bisa lanjut."
                    else "Buka halaman Autostart, aktifkan untuk LiaDo, lalu centang kotak di atas.",
                    fontSize = 12.sp, color = cs.error
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = canFinish) { Text("Selesai") } }
    )
}

/** [ok]: true = beres (centang), false = perlu tindakan (peringatan merah), null = informasi saja. */
@Composable
private fun SettingRow(
    icon: ImageVector, title: String, status: String, ok: Boolean?, action: String, th: CuteTheme, onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(th.accent.copy(alpha = if (th.dark) 0.35f else 0.4f)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ok != null) {
                    Icon(
                        if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null,
                        tint = if (ok) th.primary else cs.error, modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(status, fontSize = 12.sp, color = if (ok == false) cs.error else cs.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

private fun openAppInfo(ctx: Context) {
    try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) {}
}

private fun openLockScreen(ctx: Context) {
    try {
        if (Build.VERSION.SDK_INT >= 34)
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
        else openAppInfo(ctx)   // Xiaomi dll: Izin -> Izin lainnya
    } catch (_: Exception) { openAppInfo(ctx) }
}

private fun openNotifSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    } catch (_: Exception) { openAppInfo(ctx) }
}

internal fun openBattery(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { openAppInfo(ctx) }
    }
}

/** Halaman "Autostart" bawaan tiap merek (tidak ada API standar). Bila tidak ada yang cocok, buka info aplikasi. */
private val autostartTargets = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
)

internal fun openAutostart(ctx: Context) {
    for ((pkg, cls) in autostartTargets) {
        try { ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls))); return } catch (_: Exception) {}
    }
    openAppInfo(ctx)
}
