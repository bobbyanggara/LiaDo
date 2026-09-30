package com.cutetodo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Keadaan sinkronisasi Google Drive untuk ditampilkan di UI. */
data class SyncState(
    val on: Boolean = false,          // sudah masuk dengan Google
    val email: String? = null,
    val name: String? = null,         // nama akun Google
    val busy: Boolean = false,
    val last: Long = 0,               // waktu sinkronisasi sukses terakhir
    val error: String? = null,
    val needsLogin: Boolean = false   // izin Google dicabut / sesi habis -> perlu masuk lagi
) {
    /** Nama panggilan untuk sapaan: nama depan akun Google, kalau tidak ada diambil dari email. Null bila belum masuk. */
    val greetName: String?
        get() {
            if (!on) return null
            name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            return email?.substringBefore('@')?.split('.', '_', '-', '+')?.firstOrNull { it.isNotBlank() }
                ?.let { it.replaceFirstChar { c -> c.uppercase() } }
        }
}

class TodoVM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    private val ctx = app.applicationContext
    private val prefs = ctx.getSharedPreferences("prefs", 0)

    /** true setelah daftar tugas pertama selesai dimuat (supaya layar kosong tidak berkedip "Belum ada tugas"). */
    val ready = MutableStateFlow(false)

    // Eagerly: query database sudah berjalan sejak ViewModel dibuat (saat intro berlangsung),
    // jadi data siap ketika layar utama muncul.
    val tasks = dao.all().onEach { ready.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun day() = (System.currentTimeMillis() + TimeZone.getDefault().rawOffset) / 86_400_000L
    val streak = MutableStateFlow(if (prefs.getLong("last", -1) >= day() - 1) prefs.getInt("streak", 0) else 0)

    private fun bumpStreak() {
        val last = prefs.getLong("last", -1); val d = day()
        if (last == d) return
        val s = if (last == d - 1) prefs.getInt("streak", 0) + 1 else 1
        prefs.edit().putLong("last", d).putInt("streak", s).apply()
        streak.value = s
    }

    // ---------------- Login Google & sinkronisasi ----------------
    val sync = MutableStateFlow(SyncState(
        on = prefs.getBoolean("sync_on", false), email = prefs.getString("sync_email", null),
        name = prefs.getString("sync_name", null),
        last = prefs.getLong("sync_last", 0)))
    private val syncLock = Mutex()
    private var cachedTok: String? = null
    private var cachedAt = 0L

    /** Token akses (di-cache 40 menit). Null bila perlu masuk ulang. */
    private suspend fun accessToken(force: Boolean = false): String? {
        val t = cachedTok
        if (!force && t != null && System.currentTimeMillis() - cachedAt < 40 * 60_000L) return t
        return when (val o = GoogleAuth.silentToken(ctx)) {
            is GoogleAuth.Outcome.Token -> { cachedTok = o.accessToken; cachedAt = System.currentTimeMillis(); o.accessToken }
            is GoogleAuth.Outcome.NeedsConsent -> {
                sync.value = sync.value.copy(busy = false, needsLogin = true, error = "Masuk ulang ke Google diperlukan"); null
            }
            is GoogleAuth.Outcome.Failed -> throw o.error
        }
    }
    private var lastSig = 0

    private fun sig(): Int =
        (dao.list().sortedBy { it.uid }.joinToString("|") { "${it.uid}:${it.updatedAt}" } + "#" + dao.tombs().size).hashCode()

    /** Dipanggil setelah pengguna memilih akun Google dan menyetujui akses. */
    fun onSignedIn(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val prof = try { DriveApi(token).profile() } catch (_: Exception) { null }
            val name = prof?.first; val email = prof?.second
            cachedTok = token; cachedAt = System.currentTimeMillis()
            prefs.edit().putBoolean("sync_on", true).putString("sync_email", email).putString("sync_name", name).apply()
            sync.value = sync.value.copy(on = true, email = email, name = name, error = null, needsLogin = false)
            runSync(token)
        }
    }

    fun signOut() {
        GoogleAuth.signOut(ctx, sync.value.email)   // cabut izin akun lama -> login berikutnya menampilkan pilihan akun
        cachedTok = null
        prefs.edit().putBoolean("sync_on", false).remove("sync_email").remove("sync_name").remove("sync_last").apply()
        sync.value = SyncState()
    }

    fun reportSyncError(msg: String) { sync.value = sync.value.copy(error = msg) }

    /** Sinkronkan sekarang (aman dipanggil berkali-kali; berjalan satu per satu). */
    fun syncNow() {
        if (!sync.value.on) return
        viewModelScope.launch(Dispatchers.IO) { runSync(null) }
    }

    private suspend fun runSync(given: String?) {
        syncLock.withLock {
            sync.value = sync.value.copy(busy = true, error = null)
            try {
                val token = given ?: accessToken() ?: return
                if (sync.value.name == null) {
                    DriveApi(token).profile()?.let { (n, e) ->
                        if (n != null) {
                            prefs.edit().putString("sync_name", n).apply()
                            sync.value = sync.value.copy(name = n, email = sync.value.email ?: e)
                        }
                    }
                }
                SyncEngine.run(ctx, DriveApi(token))
                lastSig = sig()
                val now = System.currentTimeMillis()
                prefs.edit().putLong("sync_last", now).apply()
                sync.value = sync.value.copy(busy = false, last = now, error = null, needsLogin = false)
            } catch (e: AuthExpiredException) {
                cachedTok = null
                sync.value = sync.value.copy(busy = false, needsLogin = true, error = "Sesi Google berakhir, masuk ulang")
            } catch (e: java.io.IOException) {
                sync.value = sync.value.copy(busy = false, error = "Gagal terhubung. Periksa koneksi internet")
            } catch (e: Exception) {
                sync.value = sync.value.copy(busy = false, error = "Sinkronisasi gagal: ${e.message?.take(80) ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun io(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    private var pollJob: kotlinx.coroutines.Job? = null

    /**
     * Selama aplikasi terbuka: sinkron penuh sekali, lalu cek riwayat perubahan Drive tiap 3 detik.
     * Sinkron penuh hanya dijalankan bila memang ada perubahan (mis. dari HP lain), jadi terasa instan tapi hemat.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var changeToken: String? = null
            while (true) {
                val st = sync.value
                if (st.on && !st.needsLogin) {
                    try {
                        val tok = accessToken()
                        if (tok != null) {
                            val api = DriveApi(tok)
                            if (changeToken == null) {
                                val t = api.startPageToken()   // ambil penanda SEBELUM sinkron supaya tidak ada perubahan yang terlewat
                                runSync(tok)
                                changeToken = t
                            } else {
                                val (changed, next) = api.changesSince(changeToken)
                                changeToken = next
                                if (changed) runSync(tok)
                            }
                        }
                    } catch (e: AuthExpiredException) {
                        cachedTok = null      // coba token baru di putaran berikutnya
                    } catch (_: Exception) {
                        // jaringan putus sesaat: diam saja, coba lagi
                    }
                } else changeToken = null
                delay(3_000)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    @OptIn(FlowPreview::class)
    private fun startAutoSync() = viewModelScope.launch(Dispatchers.IO) {
        // setiap ada perubahan data lokal, tunggu 1 detik tenang lalu unggah otomatis
        tasks.debounce(1000).collect {
            if (sync.value.on && !sync.value.needsLogin && sig() != lastSig) runSync(null)
        }
    }

    /** Setiap daftar tugas berubah (tambah, centang, hapus, hasil sinkronisasi), widget layar utama ikut diperbarui. */
    @OptIn(FlowPreview::class)
    private fun startWidgetSync() = viewModelScope.launch(Dispatchers.IO) {
        tasks.debounce(300).collect { runCatching { WidgetUpdater.refreshNow(ctx) } }
    }

    init {
        startWidgetSync()
        startAutoSync()
        // hapus file foto yang sudah tidak dipakai tugas mana pun (mis. foto diganti / tugas dihapus)
        // ditunda 5 detik supaya tidak berebut database/CPU dengan pemuatan awal
        viewModelScope.launch(Dispatchers.IO) {
            delay(5000)
            val all = dao.list()
            Photos.cleanup(ctx, all.map { it.photo }.filter { it.isNotBlank() }.toSet())
            Voices.cleanup(ctx, all.map { it.voice }.filter { it.isNotBlank() }.toSet())
        }
    }

    fun save(t: Task) = io {
        val fixed = Reminder.normalize(t)
        val id = dao.upsert(fixed)
        val saved = fixed.copy(id = if (fixed.id == 0L) id else fixed.id)
        Reminder.cancel(ctx, saved.id)
        Reminder.schedule(ctx, saved)
    }

    fun toggleDone(t: Task) = io {
        val n = t.copy(done = !t.done)
        dao.upsert(n)
        if (n.done) { Reminder.cancel(ctx, n.id); bumpStreak() } else Reminder.schedule(ctx, n)
    }

    fun toggleSub(t: Task, i: Int) = io {
        val l = t.subs.lines().filter { it.isNotBlank() }.toMutableList()
        l[i] = (if (l[i].startsWith("1|")) "0|" else "1|") + l[i].drop(2)
        dao.upsert(t.copy(subs = l.joinToString("\n")))
    }

    fun toggleStar(t: Task) = io { dao.upsert(t.copy(starred = !t.starred)) }
    fun delete(t: Task) = io { Reminder.cancel(ctx, t.id); dao.delete(t) }
    fun clearDone() = io { dao.clearDone() }
}
