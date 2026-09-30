package com.cutetodo.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Widget layar utama "LiaDo • Tugas Hari Ini".
 *
 * - Kaca gelap ungu-malam + garis tepi & cincin progres neon yang warnanya mengikuti tema aplikasi.
 * - Menampilkan tugas yang belum selesai (jumlah baris menyesuaikan tinggi widget, 1–5 baris).
 * - Lingkaran di kiri tiap tugas bisa diketuk untuk langsung menyelesaikan tugas dari layar utama.
 * - Tombol "+" membuka aplikasi langsung ke lembar "Tugas baru".
 * - Otomatis ikut berubah setiap ada perubahan data (lihat TodoVM), ganti tema, ganti hari, dan tiap 30 menit.
 */
class TodoWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        background(ctx) { WidgetUpdater.refreshNow(it, ids) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        background(ctx) { WidgetUpdater.refreshNow(it, intArrayOf(id)) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            // ganti hari / jam / zona waktu: label "Terlambat", tanggal, dan progres "hari ini" ikut diperbarui
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                background(ctx) { WidgetUpdater.refreshNow(it) }
            else -> super.onReceive(ctx, intent)
        }
    }

    private fun background(ctx: Context, block: (Context) -> Unit) {
        val pending = goAsync()
        val app = ctx.applicationContext
        Thread {
            try { runCatching { block(app) } } finally { pending.finish() }
        }.start()
    }
}

/** Ketukan lingkaran centang di widget: tandai tugas selesai. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", 0)
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val dao = Db.get(app).dao()
                val t = dao.get(id)
                if (t != null && !t.done) {
                    dao.upsert(t.copy(done = true))
                    Reminder.cancel(app, id)
                    NotificationManagerCompat.from(app).cancel(id.toInt())
                    bumpStreak(app)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, CHEERS.random(), Toast.LENGTH_SHORT).show()
                    }
                }
                runCatching { WidgetUpdater.refreshNow(app) }
            } finally { pending.finish() }
        }.start()
    }

    /** Sama persis dengan aturan streak di TodoVM (kunci "last" & "streak"), supaya centang dari widget juga dihitung. */
    private fun bumpStreak(ctx: Context) {
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val last = prefs.getLong("last", -1)
        val d = (System.currentTimeMillis() + TimeZone.getDefault().rawOffset) / 86_400_000L
        if (last == d) return
        val s = if (last == d - 1) prefs.getInt("streak", 0) + 1 else 1
        prefs.edit().putLong("last", d).putInt("streak", s).apply()
    }

    private companion object {
        val CHEERS = listOf("Yeay, selesai!", "Keren banget!", "Satu lagi beres", "Hebat, lanjut ya!")
    }
}

object WidgetUpdater {
    const val EXTRA_NEW = "liado_widget_new_task"

    /** Diset true saat aplikasi dibuka dari tombol "+" widget; Home membaca lalu membuka lembar tugas baru. */
    val newTaskRequest = MutableStateFlow(false)

    private const val ROWS = 5
    private const val HEADER_DP = 84      // padding + header + garis pemisah
    private const val ROW_DP = 32
    private const val OVERDUE = 0xFFFF8A8F.toInt()
    private const val SOFT_WHITE = 0xB3FFFFFF.toInt()

    private val ROW = intArrayOf(R.id.wRow1, R.id.wRow2, R.id.wRow3, R.id.wRow4, R.id.wRow5)
    private val CHK = intArrayOf(R.id.wChk1, R.id.wChk2, R.id.wChk3, R.id.wChk4, R.id.wChk5)
    private val CHK_IMG = intArrayOf(R.id.wChkImg1, R.id.wChkImg2, R.id.wChkImg3, R.id.wChkImg4, R.id.wChkImg5)
    private val EMO = intArrayOf(R.id.wEmo1, R.id.wEmo2, R.id.wEmo3, R.id.wEmo4, R.id.wEmo5)
    private val TTL = intArrayOf(R.id.wTtl1, R.id.wTtl2, R.id.wTtl3, R.id.wTtl4, R.id.wTtl5)
    private val TIM = intArrayOf(R.id.wTim1, R.id.wTim2, R.id.wTim3, R.id.wTim4, R.id.wTim5)

    private val locale = Locale("id", "ID")

    /** Dipanggil MainActivity: bila dibuka dari tombol "+" widget, minta Home membuka lembar tugas baru. */
    fun handleLaunch(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_NEW, false) == true) {
            newTaskRequest.value = true
            i.removeExtra(EXTRA_NEW)   // supaya tidak terulang saat layar diputar
        }
    }

    /** Minta launcher menaruh widget di layar utama (Android 8+). false = launcher tidak mendukung, pengguna harus menambah manual. */
    fun pin(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val mgr = ctx.getSystemService(AppWidgetManager::class.java) ?: return false
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(ctx, TodoWidget::class.java), null, null)
    }

    /** Perbarui semua widget di thread latar (aman dipanggil dari thread utama). */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread { runCatching { refreshNow(app) } }.start()
    }

    /** Perbarui widget sekarang. Membaca database, jadi JANGAN dipanggil dari thread utama. */
    fun refreshNow(ctx: Context, only: IntArray? = null) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = only ?: mgr.getAppWidgetIds(ComponentName(app, TodoWidget::class.java))
        if (ids.isEmpty()) return
        val snap = snapshot(app)
        for (id in ids) {
            val opts = mgr.getAppWidgetOptions(id)
            mgr.updateAppWidget(id, build(app, snap, opts))
        }
    }

    // ------------------------------------------------------------------ data

    private class Snap(
        val accent: Int,
        val pending: List<Task>,   // belum selesai, urut: hari ini/terlambat dulu, lalu yang akan datang
        val today: Set<Long>,      // id tugas yang termasuk "hari ini"
        val done: Int, val total: Int,   // progres "hari ini" untuk cincin
        val streak: Int, val now: Long
    )

    private fun startOfTomorrow(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun snapshot(ctx: Context): Snap {
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("theme_name", null)
        val theme = Themes.firstOrNull { it.name == name } ?: Themes.firstOrNull { it.name == "Blueberry Milk" } ?: Themes[0]

        val now = System.currentTimeMillis()
        val end = startOfTomorrow(now)
        val all = Db.get(ctx).dao().list()

        // "hari ini" = jatuh tempo hari ini, atau belum selesai & (terlambat / tanpa jadwal)
        val scope = all.filter { t ->
            val d = t.dueAt
            isToday(d) || (!t.done && (d == null || d < end))
        }
        val scopeIds = scope.map { it.id }.toHashSet()
        val pending = all.filter { !it.done }.sortedWith(
            compareBy<Task>({ if (it.id in scopeIds) 0 else 1 }, { if (it.starred) 0 else 1 }, { it.dueAt ?: Long.MAX_VALUE })
        )

        val day = (now + TimeZone.getDefault().rawOffset) / 86_400_000L
        val streak = if (prefs.getLong("last", -1) >= day - 1) prefs.getInt("streak", 0) else 0

        return Snap(theme.primary.toArgb(), pending, scopeIds, scope.count { it.done }, scope.size, streak, now)
    }

    // ------------------------------------------------------------------ tampilan

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    /** Banyak baris tugas yang muat di tinggi widget saat ini (1–5). */
    private fun rowsFor(ctx: Context, o: android.os.Bundle): Int {
        val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val max = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val min = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val h = (if (portrait) max else min).takeIf { it > 0 } ?: max.takeIf { it > 0 } ?: min.takeIf { it > 0 } ?: 180
        return ((h - HEADER_DP) / ROW_DP).coerceIn(1, ROWS)
    }

    private fun build(ctx: Context, s: Snap, opts: android.os.Bundle): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_todo)
        val accent = s.accent
        val rows = rowsFor(ctx, opts)
        val width = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)

        // ---- warna aksen mengikuti tema aplikasi ----
        rv.setInt(R.id.wBorder, "setColorFilter", accent)
        rv.setInt(R.id.wBorder, "setImageAlpha", 150)
        rv.setInt(R.id.wAddBg, "setColorFilter", accent)
        rv.setTextColor(R.id.wBrand, ColorUtils.blendARGB(accent, Color.WHITE, 0.25f))

        // ---- cincin progres neon ----
        val ratio = if (s.total > 0) s.done.toFloat() / s.total else 0f
        rv.setImageViewBitmap(R.id.wRing, ringBitmap(accent, ratio))
        val count = if (s.total > 0) "${s.done}/${s.total}" else "✦"
        rv.setTextViewText(R.id.wCount, count)
        rv.setTextViewTextSize(R.id.wCount, TypedValue.COMPLEX_UNIT_SP, if (count.length > 4) 8f else 10.5f)

        // ---- judul ----
        val brandDate = SimpleDateFormat("EEE, d MMM", locale).format(s.now).uppercase(locale)
        rv.setTextViewText(R.id.wBrand, "LIADO ✦ $brandDate")
        val leftToday = s.total - s.done
        rv.setTextViewText(R.id.wSub, when {
            s.pending.isEmpty() && s.total == 0 -> "Belum ada tugas"
            s.pending.isEmpty() -> "Semua beres!"
            leftToday <= 0 -> "Hari ini beres"
            leftToday == 1 -> "1 tugas lagi, semangat!"
            else -> "$leftToday tugas hari ini"
        })

        // streak disembunyikan bila widget sempit atau belum punya streak
        val showStreak = s.streak > 0 && width >= 220
        rv.setViewVisibility(R.id.wStreak, if (showStreak) View.VISIBLE else View.GONE)
        if (showStreak) rv.setTextViewText(R.id.wStreak, "${s.streak} hari")

        // ---- daftar tugas ----
        val dateFmt = SimpleDateFormat("d MMM", locale)
        val timeFmt = SimpleDateFormat("HH:mm", locale)
        for (i in 0 until ROWS) {
            val t = s.pending.getOrNull(i)
            if (t == null || i >= rows) { rv.setViewVisibility(ROW[i], View.GONE); continue }
            rv.setViewVisibility(ROW[i], View.VISIBLE)
            rv.setTextViewText(EMO[i], t.emoji.ifBlank { "📌" })
            rv.setTextViewText(TTL[i], t.title.ifBlank { "(tanpa judul)" })

            val due = t.dueAt
            val late = due != null && due < s.now
            val label = when {
                due == null -> ""
                late && !isToday(due) -> "Terlambat"
                isToday(due) -> timeFmt.format(due)
                else -> dateFmt.format(due)
            }
            rv.setTextViewText(TIM[i], label)
            rv.setTextColor(TIM[i], if (late) OVERDUE else SOFT_WHITE)

            rv.setInt(CHK_IMG[i], "setColorFilter", if (late) OVERDUE else accent)
            rv.setOnClickPendingIntent(CHK[i], donePi(ctx, t.id))
            rv.setOnClickPendingIntent(ROW[i], openPi(ctx, 10 + i, false))
        }

        val empty = s.pending.isEmpty()
        rv.setViewVisibility(R.id.wEmpty, if (empty) View.VISIBLE else View.GONE)
        if (empty) rv.setTextViewText(
            R.id.wEmpty,
            if (s.total == 0) "Libo standby, belum ada tugas~" else "Semua tugas selesai! Kamu hebat"
        )

        // ---- ketukan ----
        rv.setOnClickPendingIntent(R.id.wRoot, openPi(ctx, 1, false))
        rv.setOnClickPendingIntent(R.id.wAdd, openPi(ctx, 2, true))
        return rv
    }

    /** Cincin progres bergaya neon: jalur redup + busur bergradasi dengan cahaya (glow) di belakangnya. */
    private fun ringBitmap(accent: Int, ratio: Float): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = 16f
        val pad = 26f
        val rect = RectF(pad, pad, size - pad, size - pad)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x33FFFFFF
        }
        c.drawOval(rect, track)

        val p = ratio.coerceIn(0f, 1f)
        if (p > 0f) {
            val light = ColorUtils.blendARGB(accent, Color.WHITE, 0.55f)
            val cx = size / 2f
            c.save()
            c.rotate(-90f, cx, cx)   // mulai dari jam 12
            val shader = SweepGradient(cx, cx, intArrayOf(accent, light, accent), floatArrayOf(0f, p / 2f, p))
            val sweep = 360f * p

            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke + 8f; strokeCap = Paint.Cap.ROUND
                this.shader = shader; alpha = 150
                maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawArc(rect, 0f, sweep, false, glow)

            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
                this.shader = shader
            }
            c.drawArc(rect, 0f, sweep, false, arc)
            c.restore()
        }
        return bmp
    }

    // ------------------------------------------------------------------ PendingIntent

    private fun openPi(ctx: Context, req: Int, newTask: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        if (newTask) i.putExtra(EXTRA_NEW, true)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun donePi(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java)
            .setData(Uri.parse("liado://widget/done/$id"))   // data unik per tugas -> PendingIntent tidak saling menimpa
            .putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
