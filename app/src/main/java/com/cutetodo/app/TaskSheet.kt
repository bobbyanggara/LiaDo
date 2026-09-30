package com.cutetodo.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Penanda kecil per tugas (pilihan pengguna, satu per tugas — bukan hiasan). */
private val taskEmojis = listOf("📌", "💼", "📅", "📞", "✉️", "📚", "💳", "🛒", "🏠", "✈️", "🩺", "🎯")

private val repeatLabels = listOf("Sekali", "Setiap hari", "Hari kerja", "Mingguan", "Pilih hari")
private val dayFmt = SimpleDateFormat("EEE, d MMM", Locale("id", "ID"))
private val timeFmt = SimpleDateFormat("HH:mm", Locale("id", "ID"))

private fun dayFor(k: Int): Long =
    if (k >= 0) startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, k) }.timeInMillis)
    else startOfDay(Calendar.getInstance().apply { while (get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis)

/** Pindahkan [old] ke hari [day] dengan jam yang sama; bila belum ada jam, pakai default. */
private fun moveToDay(old: Long?, day: Long): Long {
    if (old == null) return defaultDueFor(day)
    val o = Calendar.getInstance().apply { timeInMillis = old }
    val moved = Calendar.getInstance().apply {
        timeInMillis = day
        set(Calendar.HOUR_OF_DAY, o.get(Calendar.HOUR_OF_DAY)); set(Calendar.MINUTE, o.get(Calendar.MINUTE))
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // jam yang sama sudah lewat (mis. pindah ke "Hari ini" sore hari) -> pakai jam default supaya pengingat tidak mati diam-diam
    return if (moved <= System.currentTimeMillis() && day == startOfDay(System.currentTimeMillis())) defaultDueFor(day) else moved
}

private data class SubRow(val id: Int, val text: String, val done: Boolean)

// ---------------------------------------------------------------------------------------------
// Komponen kecil untuk form
// ---------------------------------------------------------------------------------------------

/** Satu kelompok isi form dalam kartu putih dengan judul + ikon, supaya form terasa tertata. */
@Composable
private fun SheetCard(title: String, icon: ImageVector, th: CuteTheme, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = th.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun SmallLabel(text: String) =
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Baris yang bisa diketuk (waktu pengingat, foto, suara) dengan latar lembut. */
@Composable
private fun TapRow(icon: ImageVector, text: String, th: CuteTheme, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(th.accent.copy(alpha = if (th.dark) 0.5f else 0.45f))
            .clickable(onClick = onClick).heightIn(min = 52.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
        trailing()
    }
}

@Composable
private fun EmojiDot(e: String, selected: Boolean, th: CuteTheme, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape)
            .background(if (selected) th.accent else Color.Transparent)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) th.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(e, fontSize = 20.sp) }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun SoundRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, maxLines = 1)
    }
}

/** Daftar suara pengingat dalam satu dialog: ketuk untuk memilih sekaligus mendengarkan. */
@Composable
private fun SoundPickerDialog(selected: String, busy: Boolean, onSelect: (String) -> Unit, onPickFile: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { onDispose { AlarmSound.stopPreview() } }
    val hasCustom = AlarmSound.customFile(ctx) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suara pengingat") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Sounds.all.forEach { o -> SoundRow(o.label, selected == o.key) { onSelect(o.key) } }
                if (hasCustom) SoundRow(AlarmSound.label(ctx), selected == Sounds.CUSTOM) { onSelect(Sounds.CUSTOM) }
                TextButton(onClick = onPickFile, enabled = !busy) {
                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Menyalin…" else if (hasCustom) "Ganti audio dari file" else "Pilih audio dari file")
                }
                Text(
                    "Ketuk untuk mencoba. Audio sendiri: mp3/m4a/wav/ogg (maks 25 MB), dibunyikan maks 8 detik.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai") } }
    )
}

// ---------------------------------------------------------------------------------------------
// Form tugas baru / ubah tugas
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(init: Task?, th: CuteTheme, onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    val ctx = LocalContext.current
    val dark = th.dark
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isNew = init == null || init.id == 0L

    var title by remember { mutableStateOf(init?.title ?: "") }
    var note by remember { mutableStateOf(init?.note ?: "") }
    var due by remember { mutableStateOf(init?.dueAt) }
    // mode (lihat repeatLabels): 0 sekali, 1 setiap hari, 2 hari kerja, 3 mingguan, 4 pilih hari sendiri (mask = hari terpilih)
    val initRepeat = init?.repeat ?: 0
    var mode by remember { mutableIntStateOf(repeatMode(initRepeat)) }
    var mask by remember { mutableIntStateOf(customMask(initRepeat)) }
    var emoji by remember { mutableStateOf(init?.emoji ?: "📌") }
    // subtugas: daftar baris (status selesai ikut terjaga walau teksnya diedit)
    val subRows = remember {
        mutableStateListOf<SubRow>().apply {
            init?.subs?.lines()?.filter { it.isNotBlank() }?.forEachIndexed { i, l -> add(SubRow(i, l.drop(2), l.startsWith("1|"))) }
        }
    }
    var nextSubId by remember { mutableIntStateOf(subRows.size) }
    var focusSubId by remember { mutableIntStateOf(-1) }
    fun addSubAt(index: Int) {
        val id = nextSubId++
        subRows.add(index.coerceIn(0, subRows.size), SubRow(id, "", false))
        focusSubId = id
    }
    var photo by remember { mutableStateOf(init?.photo ?: "") }
    var importing by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(init?.sound ?: "") }
    var soundBusy by remember { mutableStateOf(false) }
    var showSounds by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    // ---- catatan suara: rekaman (m4a) + suara-ke-teks ----
    var voice by remember { mutableStateOf(init?.voice ?: "") }
    var recording by remember { mutableStateOf(false) }
    var recSecs by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }      // kekerasan suara saat ini (0..1)
    var peakSeen by remember { mutableIntStateOf(0) }      // puncak tertinggi selama rekaman
    val recorder = remember { VoiceRecorder(ctx) }
    fun stopRec() {
        if (!recording) return
        recording = false
        when (recorder.stop()) {
            VoiceRecorder.Result.OK -> voice = recorder.current ?: voice
            VoiceRecorder.Result.TOO_SHORT -> Toast.makeText(ctx, "Rekaman terlalu singkat", Toast.LENGTH_SHORT).show()
            VoiceRecorder.Result.SILENT -> Toast.makeText(
                ctx, "Suara tidak terekam. Cek izin mikrofon dan tombol privasi mikrofon HP (di emulator: aktifkan mikrofon virtual).",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun startRec() {
        VoicePlayer.stop(); AlarmSound.stopPreview()
        if (recorder.start()) { recSecs = 0; peakSeen = 0; level = 0f; recording = true }
        else Toast.makeText(ctx, "Mikrofon tidak bisa dipakai", Toast.LENGTH_SHORT).show()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRec() else Toast.makeText(ctx, "Izinkan mikrofon untuk merekam catatan suara", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(recording) {
        var ms = 0
        while (recording) {
            delay(100); ms += 100
            val a = recorder.amplitude()
            if (a > peakSeen) peakSeen = a
            level = level * 0.6f + (a / 12000f).coerceIn(0f, 1f) * 0.4f
            if (ms % 1000 == 0) { recSecs++; if (recSecs >= Voices.MAX_SECONDS) stopRec() }
        }
        level = 0f
    }
    // sheet ditutup saat merekam -> rekaman dibuang; pemutar dihentikan
    DisposableEffect(Unit) { onDispose { if (recording) recorder.cancel(); VoicePlayer.stop() } }

    // suara-ke-teks memakai layanan pengenal suara bawaan HP (tanpa izin tambahan)
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) {
            note = if (note.isBlank()) said.replaceFirstChar { it.uppercase() } else note.trimEnd() + " " + said
        }
    }
    fun startDictation() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Silakan bicara…")
        try { dictate.launch(i) }
        catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "HP ini belum punya layanan suara-ke-teks", Toast.LENGTH_LONG).show() }
    }

    DisposableEffect(Unit) { onDispose { AlarmSound.stopPreview() } }
    // tugas baru: langsung siap mengetik judul
    LaunchedEffect(Unit) { if (isNew && title.isBlank()) { delay(350); runCatching { titleFocus.requestFocus() } } }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            soundBusy = true
            scope.launch {
                val name = withContext(Dispatchers.IO) { AlarmSound.saveCustom(ctx, uri) }
                soundBusy = false
                if (name != null) { sound = Sounds.CUSTOM; AlarmSound.preview(ctx, Sounds.CUSTOM) }
                else Toast.makeText(ctx, "Audio tidak bisa dipakai (rusak atau lebih dari 25 MB)", Toast.LENGTH_LONG).show()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                Photos.import(ctx, uri)?.let { photo = it }
                importing = false
            }
        }
    }

    val quick = remember { listOf("Hari ini" to 0, "Besok" to 1, "Akhir pekan" to -1) }
    val dueDay = due?.let { startOfDay(it) }
    val isCustomDay = dueDay != null && quick.none { dayFor(it.second) == dueDay }
    val canSave = title.isNotBlank() && !importing && !recording && !(due != null && mode == 4 && mask == 0)
    val dueNow = due
    val pastOnce = dueNow != null && mode == 0 && dueNow <= System.currentTimeMillis()
    // kapan alarm pertama benar-benar bunyi (sudah memperhitungkan hari terpilih & waktu yang sudah lewat)
    val firstRing: Long? = dueNow?.let { d ->
        Reminder.normalize(Task(title = "", dueAt = alignDue(d, modeMask(mode, mask)), repeat = encodeRepeat(mode, mask))).dueAt
    }

    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { if (!sheet.isVisible) onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            // ---- judul sheet + tombol tutup (tetap terlihat saat isi digulir) ----
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) "Tugas baru" else "Ubah tugas", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Tutup") }
            }

            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- 1. isi tugas ----
                SheetCard("Tugas", Icons.Rounded.Edit, th) {
                    OutlinedTextField(
                        title, { title = it }, Modifier.fillMaxWidth().focusRequester(titleFocus),
                        label = { Text("Judul") }, placeholder = { Text("Mau ngerjain apa?") },
                        singleLine = true, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Catatan (opsional)") },
                        minLines = 2, maxLines = 4, shape = RoundedCornerShape(16.dp),
                        trailingIcon = { IconButton(onClick = { startDictation() }) { Icon(Icons.Rounded.Mic, "Isi catatan dengan suara", tint = th.primary) } }
                    )
                    // ---- catatan suara (rekaman) ----
                    when {
                        recording -> Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(th.accent.copy(alpha = if (th.dark) 0.5f else 0.45f))
                                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 10.dp)
                        ) {
                            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                RecDot()
                                Spacer(Modifier.width(10.dp))
                                Text("Merekam… ${Voices.fmt(recSecs)}", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Button(
                                    onClick = { stopRec() }, shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = th.primary, contentColor = th.onPrimary)
                                ) {
                                    Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Selesai")
                                }
                            }
                            // indikator suara langsung: bila tidak bergerak, mikrofon tidak menangkap apa-apa
                            LinearProgressIndicator(
                                progress = { level }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(CircleShape),
                                color = th.primary, trackColor = cs.surface
                            )
                            if (recSecs >= 2 && peakSeen < Voices.SILENT_PEAK)
                                Text("Belum ada suara terdeteksi. Coba bicara lebih dekat ke mikrofon.", fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                        }
                        voice.isNotBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            VoiceChip(voice, th, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Icon(Icons.Rounded.Mic, "Rekam ulang", tint = th.primary) }
                            IconButton(onClick = { VoicePlayer.stop(); voice = "" }) { Icon(Icons.Rounded.Close, "Hapus catatan suara") }
                        }
                        else -> TapRow(
                            Icons.Rounded.Mic, "Rekam catatan suara", th,
                            onClick = {
                                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        )
                    }
                    SmallLabel("Penanda")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // penanda lama (mis. 🌸) tetap tampil dan terpilih di tugas yang sudah ada, supaya tidak hilang diam-diam saat diedit
                        (if (emoji in taskEmojis) taskEmojis else listOf(emoji) + taskEmojis).forEach { e -> EmojiDot(e, emoji == e, th) { emoji = e } }
                    }
                    if (photo.isBlank()) {
                        TapRow(
                            Icons.Rounded.AddPhotoAlternate, if (importing) "Memproses foto…" else "Tambah foto", th,
                            onClick = { if (!importing) picker.launch("image/*") }
                        ) {
                            if (importing) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), color = th.primary, strokeWidth = 2.dp)
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(18.dp))) {
                            PhotoImage(photo, 900, Modifier.fillMaxSize())
                            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RoundIcon(Icons.Rounded.Edit, "Ganti foto") { picker.launch("image/*") }
                                RoundIcon(Icons.Rounded.Close, "Hapus foto") { photo = "" }
                            }
                            if (importing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                            }
                        }
                    }
                }

                // ---- 2. subtugas ----
                SheetCard(if (subRows.isEmpty()) "Subtugas" else "Subtugas (${subRows.size})", Icons.Rounded.FormatListBulleted, th) {
                    subRows.toList().forEach { row ->
                        key(row.id) {
                            val fr = remember { FocusRequester() }
                            LaunchedEffect(Unit) { if (focusSubId == row.id) { runCatching { fr.requestFocus() }; focusSubId = -1 } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (row.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                    tint = th.primary, modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    row.text,
                                    { v ->
                                        val i = subRows.indexOfFirst { it.id == row.id }
                                        if (i >= 0) subRows[i] = subRows[i].copy(text = v.replace("\n", " "))
                                    },
                                    Modifier.weight(1f).focusRequester(fr),
                                    placeholder = { Text("Langkah ${subRows.indexOfFirst { it.id == row.id } + 1}") },
                                    singleLine = true, shape = RoundedCornerShape(14.dp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { addSubAt(subRows.indexOfFirst { it.id == row.id } + 1) })
                                )
                                IconButton(onClick = { subRows.removeAll { it.id == row.id } }) { Icon(Icons.Rounded.Close, "Hapus subtugas") }
                            }
                        }
                    }
                    TextButton(onClick = { addSubAt(subRows.size) }, modifier = Modifier.align(Alignment.Start)) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tambah subtugas")
                    }
                }

                // ---- 3. jadwal & pengingat: hari -> jam -> ulangi -> suara ----
                SheetCard("Jadwal & pengingat", Icons.Rounded.Alarm, th) {
                    SmallLabel("Hari")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { (label, k) ->
                            val day = dayFor(k)
                            FilterChip(selected = dueDay == day, onClick = { due = moveToDay(due, day) }, label = { Text(label) })
                        }
                        FilterChip(
                            selected = isCustomDay,
                            onClick = { pickDate(ctx, dark, due) { due = moveToDay(due, startOfDay(it)) } },
                            label = { Text(if (isCustomDay) dayFmt.format(Date(due ?: 0L)) else "Pilih tanggal") }
                        )
                    }
                    if (due == null) {
                        SmallLabel("Pilih hari untuk menyalakan pengingat")
                    } else {
                        // tanggal sudah dipilih lewat chip di atas, jadi mengetuk baris ini hanya mengubah jam (satu langkah)
                        TapRow(
                            Icons.Rounded.Schedule, "Jam ${timeFmt.format(Date(due ?: 0L))}", th,
                            onClick = { pickTime(ctx, dark, due) { due = it } }
                        ) {
                            IconButton(onClick = { due = null; mode = 0; mask = 0 }) { Icon(Icons.Rounded.Close, "Hapus pengingat") }
                        }

                        SmallLabel("Ulangi")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeatLabels.forEachIndexed { i, l ->
                                FilterChip(selected = mode == i, onClick = {
                                    mode = i
                                    // pertama kali pilih "Pilih hari": mulai dari hari pada tanggal yang dipilih
                                    if (i == 4 && mask == 0)
                                        mask = 1 shl dayIndex(Calendar.getInstance().apply { timeInMillis = due ?: System.currentTimeMillis() })
                                }, label = { Text(l) })
                            }
                        }
                        if (mode == 4) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DAY_NAMES.forEachIndexed { i, n ->
                                    val on = (mask and (1 shl i)) != 0
                                    Box(
                                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                                            .background(if (on) th.primary else th.accent.copy(alpha = if (dark) 0.5f else 0.45f))
                                            .clickable { mask = mask xor (1 shl i) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            n, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium,
                                            color = if (on) th.onPrimary else cs.onSurface
                                        )
                                    }
                                }
                            }
                            if (mask == 0) Text("Pilih minimal satu hari", fontSize = 12.sp, color = cs.error)
                        }
                        // satu baris ringkasan: kapan alarm bunyi, atau peringatan bila waktunya sudah lewat
                        if (pastOnce) Text("Waktu ini sudah lewat, pengingat tidak akan bunyi", fontSize = 12.sp, color = cs.error)
                        else if (mode != 0 && firstRing != null && !(mode == 4 && mask == 0))
                            SmallLabel("Bunyi pertama: ${fmt.format(Date(firstRing))}")

                        SmallLabel("Suara pengingat")
                        val soundText = if (sound == Sounds.CUSTOM && AlarmSound.customFile(ctx) != null) AlarmSound.label(ctx)
                        else Sounds.find(sound).label
                        TapRow(Icons.Rounded.MusicNote, soundText, th, onClick = { showSounds = true }) {
                            Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ---- tombol simpan selalu terlihat di bawah ----
            Button(
                enabled = canSave,
                onClick = {
                    val d = due
                    val finalDue = if (d != null) alignDue(d, modeMask(mode, mask)) else null
                    val rep = if (d == null) 0 else encodeRepeat(mode, mask)
                    onSave((init ?: Task(title = "")).copy(
                        title = title.trim(), note = note.trim(), dueAt = finalDue, repeat = rep, emoji = emoji,
                        subs = subRows.filter { it.text.isNotBlank() }.joinToString("\n") { (if (it.done) "1|" else "0|") + it.text.trim() },
                        photo = photo, voice = voice, sound = if (d == null) "" else sound
                    ))
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 12.dp).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = th.primary, contentColor = th.onPrimary)
            ) { Text("Simpan", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        }
    }

    if (showSounds) SoundPickerDialog(
        selected = sound, busy = soundBusy,
        onSelect = { sound = it; AlarmSound.preview(ctx, it) },
        onPickFile = { soundPicker.launch(arrayOf("audio/*")) },
        onDismiss = { showSounds = false }
    )
}
