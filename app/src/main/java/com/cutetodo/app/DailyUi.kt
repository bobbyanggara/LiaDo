package com.cutetodo.app

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val dayNames = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

internal fun hhmm(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h, m)

internal fun pickTime(ctx: Context, dark: Boolean, hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    TimePickerDialog(
        dialogCtx(ctx, dark),
        { _, h, mi -> onPick(h, mi) },
        hour, minute, true
    ).show()
}

private fun dailyDaysLabel(mask: Int): String = when {
    (mask and ALL_DAYS) == ALL_DAYS -> "Setiap hari"
    mask == WEEKDAYS -> "Hari kerja (Sen–Jum)"
    mask == WEEKEND -> "Akhir pekan (Sab–Min)"
    else -> dayNames.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(", ")
}

@Composable
fun DailyRemindersDialog(
    items: List<DailyReminder>, th: CuteTheme,
    onToggle: (DailyReminder) -> Unit, onSave: (DailyReminder) -> Unit, onDelete: (DailyReminder) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var edit by remember { mutableStateOf<DailyReminder?>(null) }
    val needExact = Build.VERSION.SDK_INT >= 31 &&
        !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pengingat harian") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("Belum ada pengingat. Buat satu supaya kamu diingatkan setiap hari di jam yang kamu pilih")
                items.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(th.accent)
                            .clickable { edit = r }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val c = if (r.enabled) th.text else th.text.copy(alpha = 0.55f)
                        Column(Modifier.weight(1f)) {
                            Text(hhmm(r.hour, r.minute), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c)
                            Text(dailyDaysLabel(r.days), fontSize = 12.sp, color = c)
                            if (r.message.isNotBlank())
                                Text(r.message, fontSize = 12.sp, color = c, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = r.enabled, onCheckedChange = { onToggle(r) })
                        IconButton(onClick = { onDelete(r) }) {
                            Icon(Icons.Rounded.DeleteOutline, "Hapus pengingat", tint = c)
                        }
                    }
                }
                OutlinedButton(onClick = { edit = DailyReminder() }) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tambah pengingat")
                }
                if (needExact) {
                    Text(
                        "Agar tepat waktu, izinkan \"Alarm & pengingat\" untuk aplikasi ini. Tanpa izin, notifikasi bisa terlambat beberapa menit.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 31)
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Buka pengaturan alarm") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )

    edit?.let { r ->
        DailyEditDialog(r, th.dark, onDismiss = { edit = null }) { onSave(it); edit = null }
    }
}

@Composable
private fun DailyEditDialog(init: DailyReminder, dark: Boolean, onDismiss: () -> Unit, onSave: (DailyReminder) -> Unit) {
    val ctx = LocalContext.current
    var hour by remember { mutableIntStateOf(init.hour) }
    var minute by remember { mutableIntStateOf(init.minute) }
    var message by remember { mutableStateOf(init.message) }
    var days by remember { mutableIntStateOf(init.days) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (init.id == 0) "Pengingat baru" else "Ubah pengingat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime(ctx, dark, hour, minute) { h, m -> hour = h; minute = m } }) {
                    Text("${hhmm(hour, minute)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    message, { message = it }, label = { Text("Pesan (kosongkan = otomatis)") },
                    shape = RoundedCornerShape(16.dp), minLines = 2
                )
                Text("Ulangi pada", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayNames.forEachIndexed { i, n ->
                        FilterChip(
                            selected = (days and (1 shl i)) != 0,
                            onClick = { days = days xor (1 shl i) },
                            label = { Text(n) }
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { days = ALL_DAYS }) { Text("Setiap hari") }
                    TextButton(onClick = { days = WEEKDAYS }) { Text("Hari kerja") }
                    TextButton(onClick = { days = WEEKEND }) { Text("Akhir pekan") }
                }
                if (days == 0) Text("Pilih minimal satu hari", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = days != 0, onClick = {
                onSave(init.copy(hour = hour, minute = minute, message = message.trim(), days = days, enabled = true))
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
