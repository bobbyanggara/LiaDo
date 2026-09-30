package com.cutetodo.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val ALL_DAYS = 0b1111111   // bit 0 = Senin ... bit 6 = Minggu
const val WEEKDAYS = 0b0011111   // Sen–Jum
const val WEEKEND = 0b1100000    // Sab–Min

/**
 * Task.repeat: 0 = sekali, 1 = harian, 2 = mingguan, > 100 = hari pilihan sendiri (mask = repeat - 100).
 * Hari pilihan disimpan di kolom `repeat` yang sudah ada, jadi skema Room TIDAK berubah (data lama aman)
 * dan backup/restore JSON tetap kompatibel.
 */
const val CUSTOM_BASE = 100

val DAY_NAMES = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")

fun customMask(repeat: Int): Int = if (repeat > CUSTOM_BASE) (repeat - CUSTOM_BASE) and ALL_DAYS else 0

/** Senin = 0 ... Minggu = 6 */
fun dayIndex(c: Calendar): Int = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7

fun daysLabel(mask: Int): String = when {
    (mask and ALL_DAYS) == ALL_DAYS -> "Setiap hari"
    mask == WEEKDAYS -> "Hari kerja"
    mask == WEEKEND -> "Akhir pekan"
    else -> DAY_NAMES.filterIndexed { i, _ -> (mask and (1 shl i)) != 0 }.joinToString(", ")
}

fun repeatLabel(repeat: Int): String = when {
    repeat == 1 -> "harian"
    repeat == 2 -> "mingguan"
    repeat > CUSTOM_BASE -> daysLabel(customMask(repeat))
    else -> ""
}

/** Pilihan "Ulangi" di form (0 Sekali, 1 Setiap hari, 2 Hari kerja, 3 Mingguan, 4 Pilih hari) -> nilai Task.repeat. Skema database tidak berubah. */
fun encodeRepeat(mode: Int, mask: Int): Int = when (mode) {
    1 -> 1
    2 -> CUSTOM_BASE + WEEKDAYS
    3 -> 2
    4 -> CUSTOM_BASE + (mask and ALL_DAYS)
    else -> 0
}

/** Kebalikan [encodeRepeat]: nilai Task.repeat (lama maupun baru) -> pilihan di form. */
fun repeatMode(repeat: Int): Int = when {
    repeat == 1 -> 1
    repeat == 2 -> 3
    repeat > CUSTOM_BASE -> when (customMask(repeat)) { ALL_DAYS -> 1; WEEKDAYS -> 2; else -> 4 }
    else -> 0
}

/** Hari yang berlaku untuk pilihan [mode] (0 = tidak dibatasi hari). */
fun modeMask(mode: Int, mask: Int): Int = when (mode) { 2 -> WEEKDAYS; 4 -> mask and ALL_DAYS; else -> 0 }

/** Geser [due] ke hari terdekat (mulai hari itu sendiri) yang termasuk dalam [mask], jam tetap sama. */
fun alignDue(due: Long, mask: Int): Long {
    if ((mask and ALL_DAYS) == 0) return due
    val c = Calendar.getInstance().apply { timeInMillis = due }
    repeat(7) {
        if ((mask and (1 shl dayIndex(c))) != 0) return c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
    }
    return due
}

object Reminder {
    private fun pi(ctx: Context, id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Alarm sementara untuk "Tunda" tugas berulang; beda action dari [pi] sehingga tidak menimpa jadwal aslinya. */
    private fun snoozePi(ctx: Context, id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, AlarmReceiver::class.java).setAction("snooze").putExtra("id", id).putExtra("snooze", true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Pakai setAlarmClock: alarm "level jam weker" yang tetap bunyi tepat waktu walau aplikasi di-close /
     * di-swipe dari recent apps / HP sedang Doze, dan TIDAK butuh izin exact alarm (Android 12+ menolak
     * izin itu secara default, sehingga dulu jatuh ke alarm tidak presisi yang bisa telat / tidak bunyi).
     */
    private fun setClock(ctx: Context, time: Long, target: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), target)
    }

    fun schedule(ctx: Context, t: Task) {
        val time = t.dueAt ?: return
        if (t.done || time <= System.currentTimeMillis()) return
        setClock(ctx, time, pi(ctx, t.id))
    }

    /**
     * Tunda pengingat [minutes] menit. Tugas sekali: waktunya digeser (terlihat di kartu dan bertahan setelah HP restart).
     * Tugas berulang: jadwal aslinya (jam & hari) TIDAK diubah; tunda hanya alarm sementara.
     * Mengembalikan tugas yang perlu disimpan (sama dengan [t] bila tidak ada yang berubah).
     */
    fun snooze(ctx: Context, t: Task, minutes: Int = 10): Task {
        val at = System.currentTimeMillis() + minutes * 60_000L
        if (t.repeat > 0) { setClock(ctx, at, snoozePi(ctx, t.id)); return t }
        return t.copy(dueAt = at).also { schedule(ctx, it) }
    }

    /**
     * Rapikan jadwal sebelum disimpan: tugas berulang yang waktunya sudah lewat langsung dimajukan ke jadwal
     * berikutnya (kalau tidak, alarmnya tidak pernah terpasang); tanpa waktu berarti tidak berulang.
     */
    fun normalize(t: Task, now: Long = System.currentTimeMillis()): Task {
        val due = t.dueAt ?: return if (t.repeat != 0) t.copy(repeat = 0) else t
        if (t.repeat <= 0 || due > now) return t
        return nextDue(t, now)?.let { t.copy(dueAt = it) } ?: t
    }

    /**
     * Jadwalkan ulang semua pengingat (alarm hilang saat HP restart, aplikasi diupdate, atau jam diubah).
     * Tugas berulang yang terlewat saat HP mati dibunyikan sekarang lalu dimajukan ke jadwal berikutnya.
     */
    fun rescheduleAll(ctx: Context, catchUp: Boolean = true) {
        val dao = Db.get(ctx).dao()
        val now = System.currentTimeMillis()
        var missedSound: String? = null
        dao.withDue().forEach { t ->
            val due = t.dueAt ?: return@forEach
            when {
                due > now -> schedule(ctx, t)
                catchUp && t.repeat > 0 -> {
                    Notif.show(ctx, t, fullScreen = false); if (missedSound == null) missedSound = t.sound
                    nextDue(t, now)?.let { n -> val u = t.copy(dueAt = n); dao.upsert(u); schedule(ctx, u) }
                }
            }
        }
        missedSound?.let { Notif.ring(ctx, it) }
    }

    /** Waktu pengingat berikutnya untuk tugas berulang (null bila sekali). */
    fun nextDue(t: Task, now: Long = System.currentTimeMillis()): Long? {
        val base = t.dueAt ?: return null
        if (t.repeat <= 0) return null
        val mask = customMask(t.repeat)
        val step = if (t.repeat == 2) 7 else 1
        val c = Calendar.getInstance().apply { timeInMillis = base }
        var guard = 0
        while (guard++ < 5000) {
            c.add(Calendar.DAY_OF_YEAR, step)
            val dayOk = mask == 0 || (mask and (1 shl dayIndex(c))) != 0
            if (c.timeInMillis > now && dayOk) return c.timeInMillis
        }
        return null
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, id)); am.cancel(snoozePi(ctx, id))
    }
}

/**
 * Suara alarm mungil (res/raw/alarm_cute.wav). Suara channel notifikasi TIDAK bisa diubah setelah channel
 * dibuat, jadi dipakai ID channel baru (…_v2) dan channel lama dihapus.
 */
object AlarmSound {
    fun uri(ctx: Context): Uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.alarm_cute}")

    private const val MAX_MS = 8000L        // batas bunyi (receiver hanya punya ~10 dtk); di ujung di-fade out
    private const val FADE_MS = 800L
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val CUSTOM_FILE = "alarm_custom"
    private const val PREF_NAME = "alarm_custom_name"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** File audio pilihan pengguna (disalin ke penyimpanan aplikasi, jadi tetap ada walau aplikasi ditutup). */
    fun customFile(ctx: Context): File? =
        File(ctx.filesDir, CUSTOM_FILE).takeIf { it.exists() && it.length() > 0 }

    fun label(ctx: Context): String =
        if (customFile(ctx) != null) prefs(ctx).getString(PREF_NAME, null) ?: "Audio pilihan" else "Bawaan (alarm imut)"

    /** Salin [uri] ke penyimpanan aplikasi + pastikan bisa diputar. Mengembalikan nama file, atau null bila gagal. Panggil di thread IO. */
    fun saveCustom(ctx: Context, uri: Uri): String? {
        val tmp = File(ctx.filesDir, "$CUSTOM_FILE.tmp")
        return try {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Audio pilihan"
            var total = 0L
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw java.io.IOException("terlalu besar")
                        o.write(buf, 0, n)
                    }
                }
            }
            val mp = MediaPlayer()
            val valid = try { mp.setDataSource(tmp.absolutePath); mp.prepare(); true } catch (_: Exception) { false } finally { mp.release() }
            if (!valid) { tmp.delete(); return null }
            tmp.renameTo(File(ctx.filesDir, CUSTOM_FILE))
            prefs(ctx).edit().putString(PREF_NAME, name).apply()
            name
        } catch (_: Exception) { tmp.delete(); null }
    }

    fun resetToDefault(ctx: Context) {
        File(ctx.filesDir, CUSTOM_FILE).delete()
        prefs(ctx).edit().remove(PREF_NAME).apply()
    }

    private fun setSource(ctx: Context, mp: MediaPlayer, key: String) {
        val custom = if (key == Sounds.CUSTOM) customFile(ctx) else null
        if (custom != null) mp.setDataSource(custom.absolutePath)
        else ctx.resources.openRawResourceFd(Sounds.find(key).res).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Putar suara pengingat [key] (kalau audio pilihan rusak / hilang jatuh ke suara bawaan) langsung lewat MediaPlayer
     * di stream alarm, TIDAK bergantung pada suara channel notifikasi. Memblok maks ~8 dtk, panggil dari thread background.
     */
    fun play(ctx: Context, key: String = "") {
        if (key == Sounds.CUSTOM && customFile(ctx) != null && playOnce(ctx, key)) return
        playOnce(ctx, if (key == Sounds.CUSTOM) "" else key)
    }

    /** true = berhasil diputar; false = sumber tidak bisa diputar. */
    private fun playOnce(ctx: Context, key: String): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(true)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs())
            setSource(ctx, mp, key)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> ok.set(false); done.countDown(); true }
            mp.prepareAsync()
            if (!done.await(MAX_MS - FADE_MS, TimeUnit.MILLISECONDS)) {
                // audio panjang: fade out lalu berhenti
                try { for (i in 9 downTo 0) { mp.setVolume(i / 10f, i / 10f); Thread.sleep(FADE_MS / 10) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            ok.set(false)
        } finally {
            try { mp.release() } catch (_: Exception) {}
        }
        return ok.get()
    }

    // ---- pratinjau di form (tidak memblok; tap suara lain menghentikan yang sedang bunyi) ----
    @Volatile private var previewMp: MediaPlayer? = null

    fun stopPreview() {
        previewMp?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        previewMp = null
    }

    fun preview(ctx: Context, key: String) {
        stopPreview()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs())
            setSource(ctx, mp, key)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { try { it.release() } catch (_: Exception) {}; if (previewMp === it) previewMp = null }
            mp.setOnErrorListener { p, _, _ -> try { p.release() } catch (_: Exception) {}; if (previewMp === p) previewMp = null; true }
            previewMp = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            previewMp = null
        }
    }

    /** Channel tanpa suara (suara diputar oleh [play]); hanya getar. Suara channel tidak bisa diubah setelah dibuat -> pakai ID baru. */
    fun silentChannel(ctx: Context, id: String, name: String, vararg oldIds: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        oldIds.forEach { nm.deleteNotificationChannel(it) }
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 90, 120)
        }
        nm.createNotificationChannel(ch)
    }

    fun channel(ctx: Context, id: String, name: String, vararg oldIds: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        oldIds.forEach { nm.deleteNotificationChannel(it) }
        val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                uri(ctx),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)   // bunyi lewat volume alarm, tetap terdengar walau mode senyap
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 90, 120)
        }
        nm.createNotificationChannel(ch)
    }
}

object Notif {
    private const val CH = "reminder_v4"

    private fun act(ctx: Context, id: Long, a: String) = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, ActionReceiver::class.java).setAction(a).putExtra("id", id).putExtra("act", a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun createChannel(ctx: Context) {
        AlarmSound.silentChannel(ctx, CH, "Pengingat tugas", "reminder", "reminder_v2", "reminder_v3")
        // sisa fitur pengingat harian yang sudah dihapus
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("daily"); nm.deleteNotificationChannel("daily_v2")
        }
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove("daily_reminders").apply()
    }

    @Volatile private var largeIconCache: android.graphics.Bitmap? = null

    /** Logo LiaDo (diperkecil ke ~128 px) sebagai ikon besar notifikasi; di-cache supaya tidak didecode berulang. */
    private fun largeIcon(ctx: Context): android.graphics.Bitmap? = largeIconCache ?: try {
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.logo_app, o).also { largeIconCache = it }
    } catch (_: Throwable) { null }

    /** [fullScreen]: nyalakan layar & tampil di atas layar kunci. Dimatikan untuk pengingat yang terlewat (mis. saat HP baru menyala). */
    fun show(ctx: Context, t: Task, fullScreen: Boolean = true) {
        createChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // isi notifikasi: judul, catatan, lalu daftar subtugas (○ belum, ✓ sudah) dengan penghitung
        val subLines = t.subs.lines().filter { it.isNotBlank() }
        val subDone = subLines.count { it.startsWith("1|") }
        val note = t.note.trim()
        val title = "${t.emoji} ${t.title}".trim()
        val pending = subLines.filterNot { it.startsWith("1|") }.map { it.drop(2).trim() }.filter { it.isNotEmpty() }
        // tampilan ringkas (satu baris): catatan bila ada, kalau tidak subtugas yang tersisa
        val short = when {
            note.isNotEmpty() -> note.replace(Regex("\\s*\\n+\\s*"), " ")
            pending.isNotEmpty() -> pending.joinToString(", ")
            else -> "Waktunya! Semangat ya"
        }
        // tampilan lebar (ditarik ke bawah): catatan, baris kosong, lalu subtugas rapi
        val big = buildString {
            if (note.isNotEmpty()) append(note)
            if (subLines.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Subtugas  $subDone/${subLines.size}")
                subLines.forEach { l ->
                    val txt = l.drop(2).trim()
                    if (txt.isNotEmpty()) append("\n").append(if (l.startsWith("1|")) "✓  " else "○  ").append(txt)
                }
            }
        }
        val full = PendingIntent.getActivity(
            ctx, t.id.toInt(),
            Intent(ctx, AlarmActivity::class.java).putExtra("id", t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF5B8DEF.toInt())           // biru LiaDo untuk lingkaran ikon & nama aplikasi
            .setLargeIcon(largeIcon(ctx))
            .setContentTitle(title)
            .setContentText(short)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (fullScreen) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // isi tampil penuh di layar kunci
            .setVibrate(longArrayOf(0, 120, 90, 120))   // Android < 8; di 8+ diatur channel
            .setContentIntent(open)
            .addAction(0, "Selesai", act(ctx, t.id, "done"))
            .addAction(0, "Tunda 10 mnt", act(ctx, t.id, "snooze"))
            .setAutoCancel(true)
        if (fullScreen) b.setFullScreenIntent(full, true)
        if (big.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(big))
        val n = b.build()
        try { NotificationManagerCompat.from(ctx).notify(t.id.toInt(), n) } catch (_: SecurityException) {}
    }

    /** Bunyikan suara pengingat (blocking, panggil dari thread background setelah [show]). */
    fun ring(ctx: Context, sound: String = "") = AlarmSound.play(ctx, sound)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", 0)
        val snoozed = i.getBooleanExtra("snooze", false)   // alarm hasil "Tunda": jadwal berulang tidak dimajukan lagi
        val pending = goAsync()
        Thread {
            try {
                val dao = Db.get(ctx).dao()
                val t = dao.get(id)
                if (t != null && !t.done) {
                    runCatching { Notif.show(ctx, t) }
                    if (!snoozed) runCatching {
                        val next = Reminder.nextDue(t)
                        if (next != null) {
                            val n = t.copy(dueAt = next)
                            dao.upsert(n)
                            Reminder.schedule(ctx, n)
                        }
                    }
                    runCatching { Notif.ring(ctx, t.sound) }   // terakhir: jadwal berikutnya sudah aman walau proses diakhiri
                }
            } finally { runCatching { WidgetUpdater.refreshNow(ctx) }; pending.finish() }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try { Reminder.rescheduleAll(ctx) }   // boot, update aplikasi, ganti jam / zona waktu
            finally { runCatching { WidgetUpdater.refreshNow(ctx) }; pending.finish() }
        }.start()
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", 0)
        val act = i.getStringExtra("act")
        val p = goAsync()
        Thread {
            try {
                val dao = Db.get(ctx).dao()
                val t = dao.get(id)
                if (t != null) {
                    if (act == "done") { dao.upsert(t.copy(done = true)); Reminder.cancel(ctx, id) }
                    else { val n = Reminder.snooze(ctx, t); if (n.dueAt != t.dueAt) dao.upsert(n) }
                }
                NotificationManagerCompat.from(ctx).cancel(id.toInt())
            } finally { runCatching { WidgetUpdater.refreshNow(ctx) }; p.finish() }
        }.start()
    }
}
