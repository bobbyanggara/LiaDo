package com.cutetodo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Pengingat harian buatan sendiri. [days] = bitmask hari: bit 0 = Senin ... bit 6 = Minggu. */
data class DailyReminder(
    val id: Int = 0,
    val hour: Int = 8,
    val minute: Int = 0,
    val message: String = "",        // kosong = pesan otomatis (ringkasan tugas)
    val days: Int = ALL_DAYS,
    val enabled: Boolean = true
) {
    fun hasDay(i: Int) = (days and (1 shl i)) != 0
}

/**
 * Disimpan di SharedPreferences (JSON), sengaja TIDAK di Room supaya skema database tidak berubah
 * (Db memakai fallbackToDestructiveMigration, jadi menaikkan versi akan menghapus semua tugas).
 */
object DailyReminders {
    private const val KEY = "daily_reminders"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<DailyReminder> {
        val s = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                DailyReminder(
                    id = o.getInt("id"), hour = o.getInt("hour"), minute = o.getInt("minute"),
                    message = o.optString("message"), days = o.optInt("days", ALL_DAYS),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun persist(ctx: Context, list: List<DailyReminder>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute)
                .put("message", it.message).put("days", it.days).put("enabled", it.enabled))
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    /** Waktu bunyi berikutnya (epoch ms) setelah [from], atau null bila tidak ada hari yang dipilih. */
    fun nextTrigger(r: DailyReminder, from: Long = System.currentTimeMillis()): Long? {
        if ((r.days and ALL_DAYS) == 0) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (c.timeInMillis > from && r.hasDay(dayIndex(c))) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun pi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, 500_000 + id, Intent(ctx, DailyReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, r: DailyReminder) {
        cancel(ctx, r.id)
        if (!r.enabled) return
        val t = nextTrigger(r) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, r.id)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
        else
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
    }

    fun cancel(ctx: Context, id: Int) =
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id))

    fun scheduleAll(ctx: Context) = load(ctx).forEach { schedule(ctx, it) }
}

object DailyNotif {
    private const val CH = "daily_v2"

    fun createChannel(ctx: Context) {
        AlarmSound.channel(ctx, CH, "Pengingat harian", "daily")
    }

    fun show(ctx: Context, r: DailyReminder, pending: Int) {
        createChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = r.message.ifBlank {
            if (pending > 0) "Ada $pending tugas yang perlu diselesaikan hari ini"
            else "Tidak ada tugas tertunda. Nikmati harimu!"
        }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Pengingat harian")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(AlarmSound.uri(ctx))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1_000_000 + r.id, n) } catch (_: SecurityException) {}
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val pending = goAsync()
        Thread {
            try {
                val r = DailyReminders.load(ctx).firstOrNull { it.id == id }
                if (r != null && r.enabled) {
                    val now = Calendar.getInstance()
                    if (r.hasDay(dayIndex(now))) {
                        val endOfDay = (now.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                        }.timeInMillis
                        val count = Db.get(ctx).dao().pendingUntil(endOfDay)
                        DailyNotif.show(ctx, r, count)
                    }
                    DailyReminders.schedule(ctx, r)   // jadwalkan kemunculan berikutnya
                }
            } finally { pending.finish() }
        }.start()
    }
}
