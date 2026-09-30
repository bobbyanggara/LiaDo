package com.cutetodo.app

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val todoVm: TodoVM by viewModels()

    override fun onStart() {
        super.onStart()
        todoVm.startPolling()   // tarik perubahan dari device lain sekarang, lalu tiap 30 detik (diam bila belum masuk Google)
    }

    override fun onStop() {
        todoVm.stopPolling()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WidgetUpdater.handleLaunch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Semua pekerjaan berat dipindah ke thread latar supaya frame pertama cepat muncul:
        // membuka database, membuat channel notifikasi, lalu (setelah layar tenang) memasang ulang alarm.
        val app = applicationContext
        Thread {
            try { Db.get(app).openHelper.writableDatabase } catch (_: Exception) {}
            try { Notif.createChannel(app) } catch (_: Exception) {}
            try { Thread.sleep(1500); Reminder.rescheduleAll(app, catchUp = false) } catch (_: Exception) {}
        }.start()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        WidgetUpdater.handleLaunch(intent)   // dibuka dari tombol "+" widget -> langsung buka lembar tugas baru
        WidgetUpdater.refreshAsync(app)      // pastikan widget di layar utama selalu segar saat aplikasi dibuka
        // ViewModel dibuat sekarang: query daftar tugas sudah jalan selama intro, jadi data siap saat layar utama tampil
        val vmNow = todoVm
        setContent {
            // intro hanya tampil sekali per peluncuran (rememberSaveable: tidak diulang saat layar diputar)
            var showIntro by rememberSaveable { mutableStateOf(true) }
            Crossfade(showIntro, animationSpec = tween(500), label = "intro") { intro ->
                if (intro) IntroScreen { showIntro = false } else App(vmNow)
            }
        }
    }
}

internal val fmt = SimpleDateFormat("EEE, d MMM • HH:mm", Locale("id", "ID"))

private val filters = listOf("Semua", "Hari ini", "Bintang", "Selesai")

internal fun isToday(ms: Long?): Boolean {
    ms ?: return false
    val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = ms }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** Dialog bawaan Android (date/time picker) ikut gelap di tema gelap, supaya tidak "menyilaukan" & tetap terbaca. */
internal fun dialogCtx(ctx: Context, dark: Boolean): Context = ContextThemeWrapper(
    ctx, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun pickDate(ctx: Context, dark: Boolean, cur: Long?, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = cur ?: System.currentTimeMillis() }
    DatePickerDialog(dialogCtx(ctx, dark), { _, y, m, d ->
        c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
        onPick(c.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

internal fun pickDateTime(ctx: Context, dark: Boolean, cur: Long?, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = cur ?: (System.currentTimeMillis() + 3_600_000) }
    val themed = dialogCtx(ctx, dark)
    DatePickerDialog(themed, { _, y, m, d ->
        TimePickerDialog(themed, { _, h, mi ->
            c.set(y, m, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
            onPick(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

/** Pilih jam saja (tanggal tetap), untuk mengubah jam pengingat dalam satu langkah. */
internal fun pickTime(ctx: Context, dark: Boolean, cur: Long?, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = cur ?: (System.currentTimeMillis() + 3_600_000) }
    TimePickerDialog(dialogCtx(ctx, dark), { _, h, mi ->
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        onPick(c.timeInMillis)
    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
}

@Composable
fun App(vm: TodoVM = viewModel()) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    // kunci baru "theme_name": pilihan lama (indeks "theme", mis. Sakura Pink) diabaikan sehingga semua mulai dari Blueberry Milk
    var themeIdx by remember {
        mutableIntStateOf(
            Themes.indexOfFirst { it.name == prefs.getString("theme_name", DEFAULT_THEME_NAME) }
                .takeIf { it >= 0 } ?: DEFAULT_THEME
        )
    }
    val th = Themes[themeIdx]
    val scheme = remember(themeIdx) { th.toColorScheme() }

    // samakan warna window (status bar, navigation bar, latar saat keyboard/overscroll) dengan tema
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.setBackgroundDrawable(ColorDrawable(th.bg.toArgb()))
        window.statusBarColor = th.bg.toArgb()
        window.navigationBarColor = th.bg.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !th.dark
            isAppearanceLightNavigationBars = !th.dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = appShapes
    ) {
        Home(vm, th, onTheme = {
            themeIdx = it; prefs.edit().putString("theme_name", Themes[it].name).apply()
            WidgetUpdater.refreshAsync(view.context)
        })
    }
}

private val appShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)
)

/** Tema awal saat aplikasi pertama kali dibuka: Blueberry Milk. */
private const val DEFAULT_THEME_NAME = "Blueberry Milk"
private val DEFAULT_THEME = Themes.indexOfFirst { it.name == DEFAULT_THEME_NAME }.coerceAtLeast(0)

const val NAMA = "Sayang" // sapaan bawaan bila belum masuk Google; setelah masuk dipakai nama akun Google
private val quotes = listOf(
"Halo, aku Libo! Siap bantu tugasmu hari ini", "Baterai semangat: penuh", "Memindai tugas… semua terkendali!",
"Satu langkah kecil tetap kemajuan", "Istirahat itu fitur, bukan bug", "Pelan-pelan juga sampai, sistem mencatat"
)
private val cheers = listOf(
"Satu beres, mantap!", "Tugas terkompilasi, kerja bagus", "Level naik! Lanjut satu lagi", "Bzzt! Keren banget", "Kamu jago, Libo terkesan", "Yeay, tercentang!"
)

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when { h < 4 -> "Belum tidur ya"; h < 11 -> "Selamat pagi"; h < 15 -> "Selamat siang"; h < 18 -> "Selamat sore"; else -> "Selamat malam" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(vm: TodoVM, th: CuteTheme, onTheme: (Int) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val haptic = LocalHapticFeedback.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val syncSt by vm.sync.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showNotif by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var confirm by remember { mutableIntStateOf(0) }   // 1 = hapus yang selesai
    var showSetup by remember { mutableStateOf(!prefs.getBoolean("setup_done", false) && needsBackgroundSetup(ctx)) }
    var menu by remember { mutableStateOf(false) }
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var calSel by rememberSaveable { mutableStateOf(startOfDay(System.currentTimeMillis())) }
    var calMonth by rememberSaveable { mutableStateOf(startOfMonth(System.currentTimeMillis())) }
    var calQuery by rememberSaveable { mutableStateOf("") }
    val quote = remember { quotes.random() }
    val newFromWidget by WidgetUpdater.newTaskRequest.collectAsStateWithLifecycle()
    LaunchedEffect(newFromWidget) {
        if (newFromWidget) {
            WidgetUpdater.newTaskRequest.value = false
            showCalendar = false
            editing = null
            showDialog = true
        }
    }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }

    // ---- login Google (sinkronisasi antar device) ----
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        when (val o = GoogleAuth.fromIntent(ctx, r.data)) {
            is GoogleAuth.Outcome.Token -> vm.onSignedIn(o.accessToken)
            is GoogleAuth.Outcome.Failed -> if (r.resultCode != Activity.RESULT_CANCELED) vm.reportSyncError("Gagal masuk dengan Google")
            else -> {}
        }
    }
    fun signInGoogle() = GoogleAuth.authorize(ctx) { o ->
        when (o) {
            is GoogleAuth.Outcome.Token -> vm.onSignedIn(o.accessToken)
            is GoogleAuth.Outcome.NeedsConsent -> authLauncher.launch(IntentSenderRequest.Builder(o.intentSender).build())
            is GoogleAuth.Outcome.Failed -> vm.reportSyncError("Gagal masuk dengan Google")
        }
    }

    // ---- efek: suara, confetti, ucapan maskot, viewer foto ----
    var soundOn by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var cheer by remember { mutableStateOf<String?>(null) }
    var cheerId by remember { mutableIntStateOf(0) }
    var checkTick by remember { mutableIntStateOf(0) }   // naik tiap tugas dicentang -> maskot ikut bereaksi
    var burst by remember { mutableStateOf<Burst?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val sfx = remember { Sfx(ctx) }
    DisposableEffect(Unit) { onDispose { sfx.release(); VoicePlayer.stop() } }
    // suara efek disiapkan di thread latar setelah layar utama tampil
    LaunchedEffect(Unit) { delay(600); withContext(Dispatchers.Default) { sfx.warmUp() } }
    LaunchedEffect(cheerId) { if (cheerId > 0) { delay(2600); cheer = null } }

    val fx = remember {
        Fx(
            scope = scope,
            onChecked = { t, at ->
                val last = tasks.none { !it.done && it.id != t.id }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (soundOn) { if (last) sfx.fanfare() else sfx.pop() }
                burst = Burst((burst?.id ?: 0) + 1, at, last)
                cheer = if (last) "Semua beres! Libo masuk mode santai" else cheers.random()
                cheerId++
                checkTick++
            },
            onPhoto = { viewing = it }
        )
    }

    // dihitung ulang hanya bila daftar / pencarian / filter berubah (bukan tiap recompose)
    val shown = remember(tasks, q, filter) {
        tasks.filter { t ->
            (q.isBlank() || t.title.contains(q, true) || t.note.contains(q, true) || t.subs.contains(q, true)) && when (filter) {
                1 -> isToday(t.dueAt) && !t.done
                2 -> t.starred
                3 -> t.done
                else -> true
            }
        }
    }
    val doneCount = remember(tasks) { tasks.count { it.done } }
    val progress = if (tasks.isEmpty()) 0f else doneCount / tasks.size.toFloat()
    val animProgress by animateFloatAsState(progress, tween(600), label = "progress")
    val mood = when { tasks.isEmpty() -> 0; progress >= 1f -> 3; progress > 0f -> 2; else -> 1 }
    val ink = MaterialTheme.colorScheme.onSurface

    BackHandler(enabled = showCalendar) { showCalendar = false }

    val onDelete: (Task) -> Unit = { t ->
        vm.delete(t)
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar("Tugas dihapus", "Batalkan", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.save(t)
        }
    }
    val onEdit: (Task) -> Unit = { editing = it; showDialog = true }

    CompositionLocalProvider(LocalFx provides fx) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snack) },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = {
                            // dari kalender: tugas baru langsung memakai tanggal yang sedang dipilih
                            editing = if (showCalendar) Task(title = "", dueAt = defaultDueFor(calSel)) else null
                            showDialog = true
                        },
                        containerColor = th.primary, contentColor = th.onPrimary, shape = RoundedCornerShape(20.dp),
                        icon = { Icon(Icons.Rounded.Add, null) },
                        text = { Text("Tugas baru", fontWeight = FontWeight.SemiBold) }
                    )
                }
            ) { pad ->
                if (showCalendar) CalendarScreen(
                    tasks = tasks, th = th, month = calMonth, selected = calSel, query = calQuery,
                    onMonth = { calMonth = it }, onSelect = { calSel = it }, onQuery = { calQuery = it },
                    onClose = { showCalendar = false },
                    onDone = vm::toggleDone, onStar = vm::toggleStar, onSub = vm::toggleSub,
                    onDelete = onDelete, onEdit = onEdit,
                    modifier = Modifier.padding(pad)
                ) else Column(Modifier.padding(pad)) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                            .background(Brush.verticalGradient(listOf(th.accent, MaterialTheme.colorScheme.background)))
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${greeting()}, ${syncSt.greetName ?: NAMA}", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showThemes = true }) { Icon(Icons.Rounded.Palette, "Pilih tema", tint = th.primary) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menu", tint = th.primary) }
                                HomeMenu(
                                    expanded = menu, onDismiss = { menu = false }, th = th,
                                    soundOn = soundOn, doneCount = doneCount,
                                    onSound = { soundOn = !soundOn; prefs.edit().putBoolean("sound", soundOn).apply() },
                                    onNotif = { menu = false; showNotif = true },
                                    onAccount = { menu = false; showAccount = true },
                                    onWidget = {
                                        menu = false
                                        if (WidgetUpdater.pin(ctx)) say("Pilih tempat widget di layar utama")
                                        else say("Tahan layar utama → Widget → LiaDo untuk menambahkannya")
                                    },
                                    onClear = { menu = false; confirm = 1 }
                                )
                            }
                        }
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Bottom) {
                            Mascot(
                                mood, th, Modifier.size(84.dp),
                                reactTick = checkTick, reactBig = burst?.big == true,
                                onTap = { msg -> cheer = msg; cheerId++; if (soundOn) sfx.pop() }
                            )
                            Spacer(Modifier.width(8.dp))
                            Bubble(cheer ?: quote, Modifier.weight(1f).padding(bottom = 10.dp), name = MASCOT_NAME)
                        }
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.padding(end = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$doneCount dari ${tasks.size} selesai", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (streak > 0) StreakChip(streak, th)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { animProgress },
                                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = th.primary,
                                trackColor = if (th.dark) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.surface
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(10.dp))
                        if (ready && (!syncSt.on || syncSt.needsLogin)) {
                            LoginNudge(
                                th, expired = syncSt.on && syncSt.needsLogin,
                                onLogin = { signInGoogle() }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Cari tugas…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Hapus pencarian") }
                                    // kalender: kata yang sudah diketik ikut dibawa sebagai pencarian di kalender
                                    IconButton(onClick = {
                                        val now = System.currentTimeMillis()
                                        calSel = startOfDay(now); calMonth = startOfMonth(now); calQuery = q.trim()
                                        showCalendar = true
                                    }) { Icon(Icons.Rounded.CalendarMonth, "Buka kalender", tint = th.primary) }
                                }
                            },
                            singleLine = true, shape = RoundedCornerShape(24.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            filters.forEachIndexed { i, f -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(f) }) }
                        }
                        if (shown.isEmpty()) {
                            if (ready) Column(
                                Modifier.fillMaxSize().padding(bottom = 80.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(if (tasks.isEmpty()) "Belum ada tugas" else "Tidak ada yang cocok", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (tasks.isEmpty()) "Ketuk Tugas baru untuk mulai" else "Coba ganti filter atau kata kunci",
                                    fontSize = 13.sp, color = ink.copy(alpha = 0.6f), textAlign = TextAlign.Center
                                )
                            }
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                            items(shown, key = { it.id }, contentType = { "task" }) { t ->
                                TaskCard(t, th, { vm.toggleDone(t) }, { vm.toggleStar(t) }, { i -> vm.toggleSub(t, i) }, { onDelete(t) }) { onEdit(t) }
                            }
                        }
                    }
                }
            }
            // confetti di atas segalanya; tidak menangkap sentuhan
            ConfettiLayer(burst, listOf(th.primary, th.accent, Color(0xFFFFC94D), Color(0xFF7FD6C2), Color(0xFFFF8FB0)))
        }
    }

    if (showDialog) TaskSheet(editing, th, onDismiss = { showDialog = false }) { vm.save(it); showDialog = false }

    viewing?.let { PhotoViewer(it) { viewing = null } }

    if (showNotif) NotificationSettingsDialog(th) { showNotif = false }
    if (showAccount) AccountDialog(
        th, syncSt,
        onSignIn = { signInGoogle() },
        onSync = { vm.syncNow() },
        onSignOut = { vm.signOut() },
        onDismiss = { showAccount = false }
    )
    if (showSetup) BackgroundSetupDialog(th) { prefs.edit().putBoolean("setup_done", true).apply(); showSetup = false }

    if (confirm == 1) AlertDialog(
        onDismissRequest = { confirm = 0 },
        title = { Text("Hapus tugas selesai?") },
        text = { Text("$doneCount tugas yang sudah selesai akan dihapus permanen.") },
        confirmButton = {
            TextButton(onClick = { vm.clearDone(); confirm = 0; say("Tugas selesai dihapus") }) {
                Text("Hapus", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirm = 0 }) { Text("Batal") } }
    )
    if (showThemes) AlertDialog(
        onDismissRequest = { showThemes = false },
        title = { Text("Pilih tema") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Themes.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.bg)
                            .border(2.dp, if (t == th) t.primary else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { onTheme(i); showThemes = false }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(t.primary))
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(18.dp).clip(CircleShape).background(t.accent))
                        Spacer(Modifier.width(12.dp))
                        Text(t.name, Modifier.weight(1f), color = t.text, fontWeight = FontWeight.Medium)
                        if (t == th) Icon(Icons.Rounded.Check, null, tint = t.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showThemes = false }) { Text("Tutup") } }
    )
}

/** Menu titik tiga: dikelompokkan (pengaturan / data / hapus), tiap item berikon, dan aksi berbahaya berwarna merah. */
@Composable
private fun HomeMenu(
    expanded: Boolean, onDismiss: () -> Unit, th: CuteTheme, soundOn: Boolean, doneCount: Int,
    onSound: () -> Unit, onNotif: () -> Unit, onAccount: () -> Unit, onWidget: () -> Unit, onClear: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    @Composable fun MenuLine() = Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.6f)))

    DropdownMenu(expanded, onDismiss, modifier = Modifier.widthIn(min = 250.dp)) {
        // toggle: menu tetap terbuka supaya perubahan langsung terlihat
        DropdownMenuItem(
            text = { Text("Suara efek") },
            onClick = onSound,
            leadingIcon = { Icon(if (soundOn) Icons.Rounded.MusicNote else Icons.Rounded.MusicOff, null, tint = th.primary) },
            trailingIcon = { Switch(checked = soundOn, onCheckedChange = null) }
        )
        DropdownMenuItem(
            text = { Text("Pengaturan notifikasi") },
            onClick = onNotif,
            leadingIcon = { Icon(Icons.Rounded.Notifications, null, tint = th.primary) }
        )
        DropdownMenuItem(
            text = { Text("Tambahkan ke layar utama") },
            onClick = onWidget,
            leadingIcon = { Icon(Icons.Rounded.Widgets, null, tint = th.primary) }
        )
        DropdownMenuItem(
            text = { Text("Akun Google & sinkronisasi") },
            onClick = onAccount,
            leadingIcon = { Icon(Icons.Rounded.AccountCircle, null, tint = th.primary) }
        )
        val ctx = LocalContext.current
        DropdownMenuItem(
            text = { Text("Kebijakan Privasi") },
            onClick = { Legal.open(ctx, Legal.PRIVACY, th.primary.toArgb()); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.PrivacyTip, null, tint = th.primary) }
        )
        DropdownMenuItem(
            text = { Text("Ketentuan Layanan") },
            onClick = { Legal.open(ctx, Legal.TERMS, th.primary.toArgb()); onDismiss() },
            leadingIcon = { Icon(Icons.Rounded.Description, null, tint = th.primary) }
        )
        MenuLine()
        val danger = if (doneCount > 0) cs.error else cs.onSurface.copy(alpha = 0.38f)
        DropdownMenuItem(
            text = { Text(if (doneCount > 0) "Hapus yang selesai ($doneCount)" else "Hapus yang selesai", color = danger) },
            onClick = onClear,
            enabled = doneCount > 0,
            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = danger) }
        )
    }
}

@Composable
private fun StreakChip(days: Int, th: CuteTheme) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = th.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("$days hari beruntun", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhotoViewer(name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
            PhotoImage(name, 1600, Modifier.fillMaxSize().padding(16.dp), ContentScale.Fit)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Tutup foto", tint = Color.White)
            }
        }
    }
}

@Composable
fun TaskCard(t: Task, th: CuteTheme, onDone: () -> Unit, onStar: () -> Unit, onSub: (Int) -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    val overdue = !t.done && t.dueAt != null && t.dueAt < System.currentTimeMillis()
    val ink = MaterialTheme.colorScheme.onSurface
    // diparse / diformat sekali per perubahan data, bukan setiap frame saat menggulir
    val subs = remember(t.subs) { t.subs.lines().filter { it.isNotBlank() } }
    val dueText = remember(t.dueAt, t.repeat) {
        t.dueAt?.let { d -> val r = repeatLabel(t.repeat); fmt.format(Date(d)) + if (r.isEmpty()) "" else " • $r" }
    }
    val fx = LocalFx.current
    val fallbackScope = rememberCoroutineScope()

    // Centang berubah dulu di sini supaya animasinya terlihat; baru setelah jeda singkat disimpan
    // (dan kartu pindah ke bagian "selesai").
    var checked by remember(t.done) { mutableStateOf(t.done) }
    var busy by remember(t.done) { mutableStateOf(false) }
    // posisi centang hanya dibutuhkan saat diketuk (untuk confetti) -> simpan di holder biasa, bukan state,
    // supaya menggulir tidak memicu recompose
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val fade by animateFloatAsState(if (checked) 0.5f else 1f, tween(250), label = "fade")

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Column {
            if (t.photo.isNotBlank()) {
                PhotoImage(
                    t.photo, 720,
                    Modifier.fillMaxWidth().height(150.dp).clickable { fx?.onPhoto(t.photo) }
                )
            }
            Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 2.dp), verticalAlignment = Alignment.Top) {
                CuteCheck(
                    checked = checked, color = th.primary, tickColor = th.onPrimary,
                    onClick = {
                        if (busy) return@CuteCheck
                        busy = true
                        val now = !checked
                        checked = now
                        if (now) {
                            val c = coords[0]
                            val pos = if (c != null && c.isAttached) c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f) else Offset.Zero
                            fx?.onChecked(t, pos)
                        }
                        (fx?.scope ?: fallbackScope).launch {
                            if (now) delay(520)
                            onDone()
                        }
                    },
                    modifier = Modifier.onGloballyPositioned { coords[0] = it }
                )
                Column(Modifier.weight(1f).padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        "${t.emoji} ${t.title}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        textDecoration = if (checked) TextDecoration.LineThrough else null, color = ink.copy(alpha = fade)
                    )
                    if (t.note.isNotBlank()) Text(t.note, fontSize = 13.sp, color = ink.copy(alpha = 0.7f * fade))
                    if (t.voice.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        VoiceChip(t.voice, th)
                    }
                    if (subs.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("${subs.count { it.startsWith("1|") }}/${subs.size} subtugas", fontSize = 11.sp, color = th.primary, fontWeight = FontWeight.Medium)
                        subs.forEachIndexed { i, s ->
                            val d = s.startsWith("1|")
                            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSub(i) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (d) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = th.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.drop(2), fontSize = 13.sp, textDecoration = if (d) TextDecoration.LineThrough else null)
                            }
                        }
                    }
                    if (dueText != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val c = if (overdue) th.overdue else th.primary
                            Icon(Icons.Rounded.Alarm, null, tint = c, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(dueText, fontSize = 12.sp, color = c, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                IconButton(onClick = onStar, modifier = Modifier.size(40.dp)) {
                    Icon(if (t.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (t.starred) "Hapus bintang" else "Beri bintang",
                        tint = if (t.starred) Color(0xFFFFB800) else ink.copy(alpha = 0.4f))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, "Hapus tugas", tint = ink.copy(alpha = 0.5f))
                }
            }
        }
    }
}
