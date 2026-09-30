package com.cutetodo.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clockFmt = SimpleDateFormat("HH:mm", Locale("id", "ID"))
private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID"))

/**
 * Layar pengingat yang menyala dan tampil DI ATAS layar kunci saat waktu tugas tiba
 * (dipicu lewat full-screen intent dari notifikasi). Hanya menampilkan satu tugas + tombol aksi;
 * isi aplikasi lainnya tetap terkunci sampai pengguna membuka kunci.
 */
class AlarmActivity : ComponentActivity() {
    private var taskId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        taskId = intent.getLongExtra("id", 0)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val th = Themes.firstOrNull { it.name == prefs.getString("theme_name", null) }
            ?: Themes.firstOrNull { it.name == "Blueberry Milk" } ?: Themes.first()

        setContent {
            var task by remember { mutableStateOf<Task?>(null) }
            LaunchedEffect(taskId) {
                val t = withContext(Dispatchers.IO) { Db.get(applicationContext).dao().get(taskId) }
                if (t == null || t.done) finish() else task = t
            }
            MaterialTheme(colorScheme = th.toColorScheme()) {
                Box(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(th.bg, th.accent.copy(alpha = if (th.dark) 0.85f else 0.75f)))),
                ) {
                    task?.let { t -> Content(t, th) }
                }
            }
        }
    }

    @Composable
    private fun Content(t: Task, th: CuteTheme) {
        val cs = MaterialTheme.colorScheme
        val subLines = t.subs.lines().filter { it.isNotBlank() }
        val subDone = subLines.count { it.startsWith("1|") }

        // jam & tanggal hidup di bagian atas, seperti layar alarm pada umumnya
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween
            ) {
                // ---- atas: penanda + jam ----
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.clip(CircleShape).background(th.primary.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Alarm, null, tint = th.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pengingat tugas", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = th.primary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(clockFmt.format(Date(now)), fontSize = 64.sp, fontWeight = FontWeight.Light, color = th.text)
                    Text(dateFmt.format(Date(now)), fontSize = 15.sp, color = th.text.copy(alpha = 0.7f))
                }

                // ---- tengah: lingkaran berdenyut + kartu tugas ----
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(20.dp))
                    PulseBadge(t.emoji, th)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(th.card).padding(horizontal = 22.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(t.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = th.text)
                        t.dueAt?.let { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Schedule, null, tint = th.text.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                val r = repeatLabel(t.repeat)
                                Text(
                                    fmt.format(Date(d)) + if (r.isEmpty()) "" else " • $r",
                                    fontSize = 13.sp, color = th.text.copy(alpha = 0.7f)
                                )
                            }
                        }
                        if (t.note.isNotBlank() || subLines.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(th.text.copy(alpha = 0.1f)))
                        }
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (t.note.isNotBlank()) Text(t.note.trim(), fontSize = 15.sp, color = th.text, modifier = Modifier.fillMaxWidth())
                            if (subLines.isNotEmpty()) {
                                Text("Subtugas  $subDone/${subLines.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = th.primary)
                                subLines.take(8).forEach { l ->
                                    val done = l.startsWith("1|")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                            tint = if (done) th.primary else th.text.copy(alpha = 0.45f), modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            l.drop(2).trim(), fontSize = 14.sp,
                                            color = th.text.copy(alpha = if (done) 0.5f else 0.9f)
                                        )
                                    }
                                }
                                if (subLines.size > 8) Text("+${subLines.size - 8} lainnya", fontSize = 12.sp, color = th.text.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                // ---- bawah: aksi ----
                Column(Modifier.padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { act(t.id, "done") }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = th.primary, contentColor = th.onPrimary)
                    ) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Selesai", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                    OutlinedButton(
                        onClick = { act(t.id, "snooze") }, modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("Tunda 10 menit", color = th.primary, fontWeight = FontWeight.Medium) }
                    TextButton(onClick = { openApp(t.id) }) { Text("Buka aplikasi", color = cs.onSurface.copy(alpha = 0.7f)) }
                }
            }
        }
    }

    /** Penanda tugas di dalam lingkaran dengan dua gelombang lembut yang memudar keluar. */
    @Composable
    private fun PulseBadge(emoji: String, th: CuteTheme) {
        val p by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "p"
        )
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            listOf(0f, 0.5f).forEach { off ->
                val ph = (p + off) % 1f
                Box(
                    Modifier.size(92.dp)
                        .graphicsLayer { scaleX = 1f + 0.55f * ph; scaleY = 1f + 0.55f * ph; alpha = 0.3f * (1f - ph) }
                        .background(th.primary, CircleShape)
                )
            }
            Box(
                Modifier.size(92.dp).clip(CircleShape).background(th.card).border(2.dp, th.primary.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 42.sp) }
        }
    }

    /** "done" / "snooze" diproses ActionReceiver yang sama dengan tombol di notifikasi (sekaligus menutup notifikasi). */
    private fun act(id: Long, a: String) {
        sendBroadcast(Intent(this, ActionReceiver::class.java).setAction(a).putExtra("id", id).putExtra("act", a))
        finish()
    }

    private fun openApp(id: Long) {
        NotificationManagerCompat.from(this).cancel(id.toInt())
        val go = { startActivity(Intent(this, MainActivity::class.java)); finish() }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
            })
        } else go()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        taskId = intent.getLongExtra("id", taskId)   // pengingat lain masuk saat layar ini masih terbuka
    }
}
