package com.cutetodo.app

import android.content.Context
import android.accounts.Account
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Login Google + sinkronisasi antar device.
 *
 * Data disimpan di "appDataFolder" Google Drive milik pengguna sendiri: folder tersembunyi khusus aplikasi ini,
 * tidak terlihat di Drive biasa, tidak butuh server, dan gratis. Isinya:
 *   - liado-data.json : semua tugas + catatan penghapusan
 *   - <uuid>.jpg / <uuid>.m4a : foto dan catatan suara (nama file unik, tidak pernah berubah isinya)
 */
object GoogleAuth {
    const val SCOPE_DRIVE = "https://www.googleapis.com/auth/drive.appdata"

    sealed interface Outcome {
        class Token(val accessToken: String) : Outcome
        class NeedsConsent(val intentSender: IntentSender) : Outcome
        class Failed(val error: Throwable) : Outcome
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE_DRIVE)))
        .build()

    private const val PENDING_KEY = "revoke_pending_email"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /**
     * Dipanggil saat keluar. Izin akun lama DICABUT, kalau tidak Google mengingatnya dan login berikutnya
     * langsung masuk lagi ke akun yang sama tanpa menawarkan pilihan akun.
     * Bila gagal (mis. sedang offline), pencabutan diulang otomatis sebelum login berikutnya.
     */
    fun signOut(ctx: Context, email: String?) {
        if (email.isNullOrBlank()) return
        prefs(ctx).edit().putString(PENDING_KEY, email).apply()
        revoke(ctx, email) { }
    }

    private fun revoke(ctx: Context, email: String, then: () -> Unit) {
        try {
            val req = RevokeAccessRequest.builder()
                .setAccount(Account(email, "com.google"))
                .setScopes(listOf(Scope(SCOPE_DRIVE)))
                .build()
            Identity.getAuthorizationClient(ctx).revokeAccess(req)
                .addOnSuccessListener { prefs(ctx).edit().remove(PENDING_KEY).apply(); then() }
                .addOnFailureListener { then() }   // tetap tertunda; dicoba lagi saat login berikutnya
        } catch (_: Exception) { then() }
    }

    /** Minta izin / token. Bila pengguna belum pernah setuju -> NeedsConsent (tampilkan layar pilih akun Google). */
    fun authorize(ctx: Context, done: (Outcome) -> Unit) {
        val pending = prefs(ctx).getString(PENDING_KEY, null)
        if (pending != null) { revoke(ctx, pending) { doAuthorize(ctx, done) }; return }
        doAuthorize(ctx, done)
    }

    private fun doAuthorize(ctx: Context, done: (Outcome) -> Unit) {
        Identity.getAuthorizationClient(ctx).authorize(request())
            .addOnSuccessListener { done(toOutcome(it)) }
            .addOnFailureListener { done(Outcome.Failed(it)) }
    }

    /** Hasil dari layar persetujuan (dipanggil di onActivityResult / launcher). */
    fun fromIntent(ctx: Context, data: Intent?): Outcome = try {
        toOutcome(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) { Outcome.Failed(e) }

    /** Token untuk sinkronisasi latar belakang (tanpa UI). */
    suspend fun silentToken(ctx: Context): Outcome = suspendCancellableCoroutine { c ->
        authorize(ctx) { if (c.isActive) c.resume(it) }
    }

    private fun toOutcome(r: AuthorizationResult): Outcome {
        if (r.hasResolution()) {
            val pi = r.pendingIntent ?: return Outcome.Failed(IOException("Tidak ada layar persetujuan"))
            return Outcome.NeedsConsent(pi.intentSender)
        }
        val t = r.accessToken ?: return Outcome.Failed(IOException("Token kosong"))
        return Outcome.Token(t)
    }
}

class AuthExpiredException : IOException("Sesi Google berakhir")

/** Klien REST Google Drive v3 minimal (tanpa library tambahan). Semua fungsi memblokir: panggil dari thread IO. */
class DriveApi(private val token: String) {
    class RFile(val id: String, val name: String, val modified: Long)

    private val base = "https://www.googleapis.com/drive/v3"
    private val upBase = "https://www.googleapis.com/upload/drive/v3"

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (method == "PATCH") {
                // HttpURLConnection tidak mengenal PATCH; Google menerima override lewat header ini
                requestMethod = "POST"; setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else requestMethod = method
        }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code == 401) throw AuthExpiredException()
        if (code !in 200..299) {
            val msg = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            throw IOException("Drive HTTP $code ${msg?.take(200) ?: ""}")
        }
    }

    private fun parseTime(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    } catch (_: Exception) { 0L }

    fun list(): List<RFile> {
        val out = ArrayList<RFile>()
        var page: String? = null
        do {
            val fields = URLEncoder.encode("nextPageToken,files(id,name,modifiedTime)", "UTF-8")
            var url = "$base/files?spaces=appDataFolder&pageSize=1000&fields=$fields"
            if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
            val c = open(url); check(c)
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += RFile(o.getString("id"), o.getString("name"), parseTime(o.optString("modifiedTime")))
            }
            page = j.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    fun downloadText(id: String): String {
        val c = open("$base/files/$id?alt=media"); check(c)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun downloadTo(id: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val c = open("$base/files/$id?alt=media"); check(c)
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (!tmp.renameTo(dest)) throw IOException("Gagal menyimpan ${dest.name}")
        } finally { tmp.delete() }
    }

    /** Buat file baru (existingId == null) atau timpa isinya. Mengembalikan id file. */
    fun put(name: String, bytes: ByteArray, mime: String, existingId: String?): String {
        val c: HttpURLConnection
        if (existingId != null) {
            c = open("$upBase/files/$existingId?uploadType=media&fields=id", "PATCH")
            c.doOutput = true
            c.setRequestProperty("Content-Type", mime)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        } else {
            val boundary = "liado" + System.nanoTime()
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: $mime\r\n\r\n"
            val tail = "\r\n--$boundary--"
            val h = head.toByteArray(); val t = tail.toByteArray()
            c = open("$upBase/files?uploadType=multipart&fields=id", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            c.setFixedLengthStreamingMode(h.size + bytes.size + t.size)
            c.outputStream.use { it.write(h); it.write(bytes); it.write(t) }
        }
        check(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    /** Penanda "posisi sekarang" di riwayat perubahan Drive. */
    fun startPageToken(): String {
        val c = open("$base/changes/startPageToken?spaces=appDataFolder&fields=startPageToken"); check(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("startPageToken")
    }

    /** Apakah ada perubahan sejak [token]? Mengembalikan (adaPerubahan, tokenBaru). Sangat ringan (1 request kecil). */
    fun changesSince(token: String): Pair<Boolean, String> {
        var page = token
        var any = false
        while (true) {
            val fields = URLEncoder.encode("nextPageToken,newStartPageToken,changes(fileId)", "UTF-8")
            val c = open("$base/changes?spaces=appDataFolder&pageSize=100&fields=$fields&pageToken=" + URLEncoder.encode(page, "UTF-8"))
            check(c)
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if ((j.optJSONArray("changes")?.length() ?: 0) > 0) any = true
            val next = j.optString("nextPageToken").ifBlank { null }
            if (next != null) { page = next; continue }
            return any to j.optString("newStartPageToken").ifBlank { page }
        }
    }

    fun delete(id: String) {
        val c = open("$base/files/$id", "DELETE")
        if (c.responseCode != 404) check(c)
    }

    /** Nama tampilan dan email akun Google yang sedang masuk. Null bila gagal (selain sesi berakhir). */
    fun profile(): Pair<String?, String?>? = try {
        val c = open("$base/about?fields=user(displayName,emailAddress)"); check(c)
        val u = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("user")
        u?.let { it.optString("displayName").ifBlank { null } to it.optString("emailAddress").ifBlank { null } }
    } catch (e: AuthExpiredException) { throw e } catch (_: Exception) { null }
}

/** Menggabungkan data lokal dengan data di Drive. Aturan: versi dengan waktu ubah paling baru menang (per tugas). */
object SyncEngine {
    private const val DATA_NAME = "liado-data.json"
    private const val TOMB_KEEP_MS = 90L * 24 * 3600 * 1000     // catatan penghapusan disimpan 90 hari
    private const val ORPHAN_MIN_AGE_MS = 24L * 3600 * 1000     // media yatim baru dihapus dari Drive setelah 1 hari

    /** Hasil: jumlah tugas lokal yang berubah karena sinkronisasi. */
    fun run(ctx: Context, api: DriveApi): Int {
        val dao = Db.get(ctx).dao()
        val db = Db.get(ctx)

        val files = api.list()
        val remoteNames = files.associateBy { it.name }
        val dataFile = remoteNames[DATA_NAME]

        // 1) baca data cloud
        val rTasks = HashMap<String, Task>()
        val rTombs = HashMap<String, Long>()
        if (dataFile != null) {
            val j = JSONObject(api.downloadText(dataFile.id))
            j.optJSONArray("tasks")?.let { a -> for (i in 0 until a.length()) fromJson(a.getJSONObject(i))?.let { rTasks[it.uid] = it } }
            j.optJSONArray("deleted")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); rTombs[o.getString("uid")] = o.getLong("at") }
            }
        }

        // 2) gabungkan
        val lTasks = dao.list().associateBy { it.uid }
        val lTombs = dao.tombs().associate { it.uid to it.deletedAt }
        val now = System.currentTimeMillis()

        val alive = HashMap<String, Task>()       // hasil akhir: tugas yang hidup
        val dead = HashMap<String, Long>()        // hasil akhir: tugas yang dihapus
        for (uid in lTasks.keys + rTasks.keys + lTombs.keys + rTombs.keys) {
            val l = lTasks[uid]; val r = rTasks[uid]
            val best = when {
                l == null -> r
                r == null -> l
                r.updatedAt > l.updatedAt -> r
                else -> l
            }
            val tomb = maxOf(lTombs[uid] ?: 0L, rTombs[uid] ?: 0L)
            if (best == null || tomb >= best.updatedAt && tomb > 0L) {
                if (tomb > 0L && now - tomb < TOMB_KEEP_MS) dead[uid] = tomb
            } else alive[uid] = best
        }

        // 3) media: unduh yang belum ada di device ini, unggah yang belum ada di Drive
        var mediaFailed = false
        for (t in alive.values) {
            for ((name, isPhoto) in listOf(t.photo to true, t.voice to false)) {
                if (name.isBlank()) continue
                val f = if (isPhoto) Photos.fileOf(ctx, name) else Voices.file(ctx, name)
                if (f == null) continue
                try {
                    if (!f.exists()) {
                        remoteNames[name]?.let { api.downloadTo(it.id, f) }
                    } else if (name !in remoteNames) {
                        api.put(name, f.readBytes(), if (isPhoto) "image/jpeg" else "audio/mp4", null)
                    }
                } catch (e: AuthExpiredException) { throw e } catch (e: Exception) { mediaFailed = true }
            }
        }

        // 4) terapkan ke database lokal
        var changed = 0
        db.runInTransaction(Runnable {
            for ((uid, t) in alive) {
                val l = lTasks[uid]
                if (l == null || t.updatedAt > l.updatedAt) {
                    val id = dao.insertRaw(t.copy(id = l?.id ?: 0L))
                    val saved = t.copy(id = if (l != null) l.id else id)
                    Reminder.cancel(ctx, saved.id)
                    Reminder.schedule(ctx, saved)
                    changed++
                }
            }
            for ((uid, _) in dead) {
                lTasks[uid]?.let { Reminder.cancel(ctx, it.id); dao.deleteRaw(it); changed++ }
            }
            for (uid in lTasks.keys) if (uid !in alive && uid !in dead) {
                lTasks[uid]?.let { Reminder.cancel(ctx, it.id); dao.deleteRaw(it); changed++ }   // tomb sudah kedaluwarsa
            }
            dao.clearTombs()
            dead.forEach { (uid, at) -> dao.putTomb(Tomb(uid, at)) }
        })

        // 5) unggah data bila ada yang berbeda dari isi cloud
        val remoteSame = rTasks.mapValues { it.value.updatedAt } == alive.mapValues { it.value.updatedAt } &&
            rTombs == dead
        if (!remoteSame || dataFile == null) {
            val body = JSONObject()
                .put("v", 1)
                .put("tasks", JSONArray().apply { alive.values.forEach { put(toJson(it)) } })
                .put("deleted", JSONArray().apply { dead.forEach { (u, at) -> put(JSONObject().put("uid", u).put("at", at)) } })
                .toString().toByteArray()
            api.put(DATA_NAME, body, "application/json", dataFile?.id)
        }

        // 6) bersihkan media yatim di Drive (hanya yang sudah tua, supaya tidak menabrak device lain yang sedang mengunggah)
        if (!mediaFailed) {
            val used = alive.values.flatMap { listOf(it.photo, it.voice) }.filter { it.isNotBlank() }.toSet()
            files.filter { it.name != DATA_NAME && it.name !in used && now - it.modified > ORPHAN_MIN_AGE_MS }
                .forEach { try { api.delete(it.id) } catch (e: AuthExpiredException) { throw e } catch (_: Exception) {} }
        }
        return changed
    }

    private fun toJson(t: Task) = JSONObject()
        .put("uid", t.uid).put("title", t.title).put("note", t.note)
        .put("dueAt", t.dueAt ?: JSONObject.NULL).put("repeat", t.repeat).put("emoji", t.emoji)
        .put("starred", t.starred).put("done", t.done).put("subs", t.subs)
        .put("photo", t.photo).put("sound", t.sound).put("voice", t.voice).put("updatedAt", t.updatedAt)

    private fun fromJson(o: JSONObject): Task? = try {
        Task(
            title = o.getString("title"), note = o.optString("note"),
            dueAt = if (o.isNull("dueAt")) null else o.getLong("dueAt"),
            repeat = o.optInt("repeat"), emoji = o.optString("emoji", "📌"),
            starred = o.optBoolean("starred"), done = o.optBoolean("done"), subs = o.optString("subs"),
            photo = o.optString("photo"), sound = o.optString("sound"), voice = o.optString("voice"),
            uid = o.getString("uid"), updatedAt = o.optLong("updatedAt")
        )
    } catch (_: Exception) { null }
}
