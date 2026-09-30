package com.cutetodo.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Catatan suara disimpan sebagai berkas m4a di penyimpanan internal aplikasi (filesDir/voices),
 * seperti foto: tidak butuh izin storage. Di database hanya disimpan NAMA berkasnya.
 */
object Voices {
    private const val DIR = "voices"
    const val MAX_SECONDS = 300   // rekaman otomatis berhenti setelah 5 menit
    /** Puncak amplitudo (skala 0..32767) di bawah ini dianggap rekaman kosong (mikrofon mati / tidak diizinkan). */
    const val SILENT_PEAK = 40

    private val durations = ConcurrentHashMap<String, Int>()

    fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun file(ctx: Context, name: String): File? =
        if (name.isBlank() || name.contains('/') || name.contains('\\')) null else File(dir(ctx), name)

    fun exists(ctx: Context, name: String): Boolean = file(ctx, name)?.exists() == true

    /** Durasi dalam detik (dibaca sekali lalu di-cache). Dipanggil dari thread latar. */
    fun duration(ctx: Context, name: String): Int {
        durations[name]?.let { return it }
        val f = file(ctx, name)?.takeIf { it.exists() } ?: return 0
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ((ms + 500) / 1000).toInt().also { durations[name] = it }
        } catch (e: Exception) { 0 } finally { runCatching { r.release() } }
    }

    /** Hapus rekaman yang tidak dipakai tugas mana pun (file < 10 menit dibiarkan: mungkin belum disimpan). */
    fun cleanup(ctx: Context, keep: Set<String>) {
        val now = System.currentTimeMillis()
        dir(ctx).listFiles()?.forEach { if (it.name !in keep && now - it.lastModified() > 600_000L) it.delete() }
    }

    fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
}

/** Perekam suara sederhana (AAC/m4a, mono) yang juga memantau kekerasan suara untuk mendeteksi rekaman kosong. */
class VoiceRecorder(private val ctx: Context) {
    enum class Result { OK, TOO_SHORT, SILENT }

    private var rec: MediaRecorder? = null
    /** Nama berkas rekaman yang sedang / baru saja direkam. */
    var current: String? = null
        private set
    /** Puncak amplitudo tertinggi selama rekaman ini (0..32767). */
    var peak = 0
        private set

    /** Mulai merekam. Mengembalikan false bila mikrofon tidak bisa dipakai. */
    fun start(): Boolean {
        val name = "${UUID.randomUUID()}.m4a"
        val f = File(Voices.dir(ctx), name)
        var made: MediaRecorder? = null
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            made = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)          // laju standar yang didukung semua HP
            r.setAudioEncodingBitRate(96000)
            r.setMaxDuration(Voices.MAX_SECONDS * 1000)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            rec = r; current = name; peak = 0
            true
        } catch (e: Exception) {
            runCatching { made?.release() }
            f.delete(); rec = null; current = null
            false
        }
    }

    /** Amplitudo sejak pemanggilan terakhir (0..32767); panggil berkala saat merekam. */
    fun amplitude(): Int {
        val r = rec ?: return 0
        val a = try { r.maxAmplitude } catch (e: Exception) { 0 }
        if (a > peak) peak = a
        return a
    }

    /** Berhenti dan simpan. Berkas dibuang bila terlalu singkat atau ternyata kosong (tanpa suara). */
    fun stop(): Result {
        val r = rec ?: return Result.TOO_SHORT
        val name = current
        amplitude()                    // ambil sisa sampel terakhir
        rec = null
        val ok = try { r.stop(); true } catch (e: RuntimeException) { false }
        runCatching { r.release() }
        if (!ok) { name?.let { Voices.file(ctx, it)?.delete() }; current = null; return Result.TOO_SHORT }
        if (peak < Voices.SILENT_PEAK) { name?.let { Voices.file(ctx, it)?.delete() }; current = null; return Result.SILENT }
        return Result.OK
    }

    /** Batalkan dan buang rekaman yang sedang berjalan. */
    fun cancel() {
        val name = current
        val r = rec
        rec = null
        if (r != null) { runCatching { r.stop() }; runCatching { r.release() } }
        name?.let { Voices.file(ctx, it)?.delete() }
        current = null
    }
}

/** Pemutar tunggal: hanya satu catatan suara yang bunyi pada satu waktu. [playing] = nama berkas yang sedang diputar. */
object VoicePlayer {
    var playing by mutableStateOf<String?>(null)
        private set
    private var mp: MediaPlayer? = null

    private fun say(ctx: Context, msg: String) =
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()

    fun toggle(ctx: Context, name: String) { if (playing == name) stop() else play(ctx, name) }

    fun play(ctx: Context, name: String) {
        stop()
        val app = ctx.applicationContext
        val f = Voices.file(app, name)?.takeIf { it.exists() && it.length() > 1024 }
        if (f == null) { say(app, "Berkas catatan suara rusak atau kosong"); return }
        // volume media 0 = tidak terdengar apa-apa; beri tahu (tetap diputar)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            say(app, "Volume media HP masih 0. Naikkan volume media lalu putar lagi.")

        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            FileInputStream(f).use { p.setDataSource(it.fd) }
            p.setVolume(1f, 1f)
            p.setOnPreparedListener { pp -> if (mp === pp) pp.start() }
            p.setOnCompletionListener { pp -> if (mp === pp) stop() }
            p.setOnErrorListener { pp, _, _ ->
                if (mp === pp) { stop(); say(app, "Catatan suara gagal diputar") }
                true
            }
            mp = p
            playing = name
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }
            mp = null; playing = null
            say(app, "Catatan suara gagal diputar")
        }
    }

    fun stop() {
        val p = mp
        mp = null
        playing = null
        if (p != null) { runCatching { p.stop() }; runCatching { p.release() } }
    }
}

/** Titik merah berdenyut penanda sedang merekam. */
@Composable
fun RecDot(modifier: Modifier = Modifier) {
    val a by rememberInfiniteTransition(label = "rec").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "recDot"
    )
    Box(modifier.size(12.dp).alpha(a).clip(CircleShape).background(Color(0xFFE5484D)))
}

/** Tombol putar/jeda catatan suara + durasi. Tidak tampil bila berkasnya tidak ada (mis. cadangan dari HP lain). */
@Composable
fun VoiceChip(name: String, th: CuteTheme, modifier: Modifier = Modifier, label: String = "Catatan suara") {
    val ctx = LocalContext.current
    if (!remember(name) { Voices.exists(ctx, name) }) return
    val secs by produceState(0, name) { value = withContext(Dispatchers.IO) { Voices.duration(ctx, name) } }
    val on = VoicePlayer.playing == name
    DisposableEffect(name) { onDispose { if (VoicePlayer.playing == name) VoicePlayer.stop() } }
    Row(
        modifier.clip(CircleShape).background(th.accent.copy(alpha = if (th.dark) 0.6f else 0.7f))
            .clickable { VoicePlayer.toggle(ctx, name) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(th.primary), contentAlignment = Alignment.Center) {
            Icon(
                if (on) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (on) "Jeda catatan suara" else "Putar catatan suara",
                tint = th.onPrimary, modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        if (secs > 0) {
            Spacer(Modifier.width(6.dp))
            Text(Voices.fmt(secs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
