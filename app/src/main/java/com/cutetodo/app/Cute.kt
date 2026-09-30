package com.cutetodo.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Efek yang dibagikan ke semua TaskCard (beranda maupun kalender) lewat CompositionLocal,
// jadi centang di mana pun otomatis membawa animasi + suara + getar.
// ---------------------------------------------------------------------------------------------

class Fx(
    val scope: CoroutineScope,
    val onChecked: (Task, Offset) -> Unit,
    val onPhoto: (String) -> Unit
)

val LocalFx = staticCompositionLocalOf<Fx?> { null }

/** Efek suara: file WAV pendek di res/raw, dimainkan lewat SoundPool (menghormati mode senyap). */
class Sfx(private val ctx: Context) {
    // dibuat malas: SoundPool + decode WAV tidak lagi menahan frame pertama layar utama
    private val pool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { made = true }
    }
    private var made = false
    private val popId by lazy { pool.load(ctx.applicationContext, R.raw.check, 1) }
    private val fanfareId by lazy { pool.load(ctx.applicationContext, R.raw.all_done, 1) }

    /** Dipanggil di thread latar setelah layar tampil supaya suara sudah siap saat pertama dicentang. */
    fun warmUp() { popId; fanfareId }

    fun pop() { pool.play(popId, 0.9f, 0.9f, 1, 0, 1f) }
    fun fanfare() { pool.play(fanfareId, 1f, 1f, 1, 0, 1f) }
    fun release() { if (made) pool.release() }
}

// ---------------------------------------------------------------------------------------------
// Centang: lingkaran yang terisi, tanda centang tergambar, dan sedikit memantul.
// ---------------------------------------------------------------------------------------------

private fun mix(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)

@Composable
fun CuteCheck(
    checked: Boolean,
    color: Color,
    tickColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 26.dp
) {
    val fill by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "fill")
    val tick by animateFloatAsState(if (checked) 1f else 0f, tween(240, delayMillis = if (checked) 70 else 0), label = "tick")
    val ring = MaterialTheme.colorScheme.outline
    val bounce = remember { Animatable(1f) }
    var prev by remember { mutableStateOf(checked) }

    LaunchedEffect(checked) {
        if (checked && !prev) {           // hanya saat baru dicentang, bukan saat item pertama kali muncul
            bounce.snapTo(0.72f)
            bounce.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 380f))
        }
        prev = checked
    }

    Box(
        modifier.size(46.dp).clip(CircleShape).clickable(role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(diameter).graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }) {
            val w = this.size.width
            val h = this.size.height
            val r = this.size.minDimension / 2f
            val sw = 2.dp.toPx()
            drawCircle(ring.copy(alpha = 1f - fill), radius = r - sw / 2f, style = Stroke(sw))
            drawCircle(color, radius = r * fill)
            if (tick > 0f) {
                val p1 = Offset(w * 0.28f, h * 0.52f)
                val p2 = Offset(w * 0.44f, h * 0.68f)
                val p3 = Offset(w * 0.74f, h * 0.34f)
                val a = (tick / 0.4f).coerceIn(0f, 1f)
                val b = ((tick - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val stroke = 2.6.dp.toPx()
                drawLine(tickColor, p1, mix(p1, p2, a), stroke, StrokeCap.Round)
                if (b > 0f) drawLine(tickColor, p2, mix(p2, p3, b), stroke, StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Confetti layar penuh (digambar di atas Scaffold, tidak menghalangi sentuhan).
// ---------------------------------------------------------------------------------------------

data class Burst(val id: Int, val at: Offset, val big: Boolean)

private class Particle(
    val angle: Float, val speed: Float, val size: Float,
    val color: Int, val spin: Float, val round: Boolean
)

@Composable
fun ConfettiLayer(burst: Burst?, colors: List<Color>, modifier: Modifier = Modifier) {
    if (burst != null) ConfettiBurst(burst, colors, modifier)
}

@Composable
private fun ConfettiBurst(b: Burst, colors: List<Color>, modifier: Modifier) {
    val t = remember(b.id) { Animatable(0f) }
    LaunchedEffect(b.id) {
        t.animateTo(1f, tween(if (b.big) 1500 else 850, easing = LinearOutSlowInEasing))
    }
    val parts = remember(b.id) {
        val rnd = Random(b.id)
        List(if (b.big) 70 else 18) {
            Particle(
                angle = rnd.nextFloat() * (2f * PI.toFloat()),
                speed = (if (b.big) 140f else 70f) + rnd.nextFloat() * (if (b.big) 260f else 110f),
                size = 4f + rnd.nextFloat() * 4f,
                color = rnd.nextInt(colors.size),
                spin = rnd.nextFloat() * 360f,
                round = rnd.nextBoolean()
            )
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val p = t.value
        val d = density
        val fade = (1f - p).coerceIn(0f, 1f)
        parts.forEach { q ->
            val dist = q.speed * d * p
            val x = b.at.x + cos(q.angle) * dist
            val y = b.at.y + sin(q.angle) * dist + 220f * d * p * p     // gravitasi
            val c = colors[q.color % colors.size]
            val s = q.size * d
            if (q.round) {
                drawCircle(c, s / 2f, Offset(x, y), alpha = fade)
            } else {
                rotate(q.spin + p * 360f, Offset(x, y)) {
                    drawRoundRect(c, Offset(x - s, y - s / 2f), Size(s * 2f, s), CornerRadius(s / 3f), alpha = fade)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Maskot "Libo": robot kucing holografik yang digambar dengan Canvas. Telinganya antena bercahaya,
// wajahnya layar visor neon dengan mata LED yang berkedip, cincin orbit berputar di lehernya, dan ada
// garis pindai yang menyapu layar. Ekspresinya berubah sesuai progres.
// mood: 0 standby/ngantuk (belum ada tugas), 1 santai, 2 senang, 3 pesta (semua selesai)
//
// Interaktif: ketuk maskot -> ia bereaksi (loncat, goyang, hati melayang, muter) sambil mengucap sesuatu.
// Diketuk berkali-kali dengan cepat -> pusing. Saat tugas dicentang ([reactTick] naik) ia ikut loncat/muter senang.
// ---------------------------------------------------------------------------------------------

const val MASCOT_NAME = "Libo"

private const val R_JUMP = 0
private const val R_WIGGLE = 1
private const val R_LOVE = 2
private const val R_SPIN = 3
private const val R_DIZZY = 4

private class Reaction(val id: Int, val kind: Int)

/** Ucapan per jenis reaksi (indeks = R_JUMP..R_SPIN). */
private val tapLines = arrayOf(
    arrayOf("Hop hop! Sistem lompat aktif", "Thruster siap meluncur!", "Baterai semangat: 100%"),
    arrayOf("Hihi, sensorku geli~", "Jangan colek antenaku! Bzzt~", "Ehehe, error lucu"),
    arrayOf("Hati terdeteksi: penuh", "Peluk digital dulu", "Libo sayang kamu"),
    arrayOf("Mode turbo: muter!", "Wheee~ rotasi 360°!", "Tadaa! Hologram aktif")
)
private val dizzyLines = arrayOf("Sistem pusing… rebooting", "Error! Mataku muter!", "Stop stop, overheat!")

private fun reactionMs(kind: Int) = when (kind) {
    R_JUMP -> 650; R_WIGGLE -> 750; R_LOVE -> 1200; R_SPIN -> 850; else -> 1500
}

private val SIGNS = floatArrayOf(-1f, 1f)
private val HEART_PINK = Color(0xFFFF5C8A)
private val NEON = Color(0xFF7DF9FF)        // cahaya mata, mulut, dan antena
private val VISOR = Color(0xFF14102B)       // layar wajah
private val NAVY = Color(0xFF1B1433)        // bayangan badan
private val STAR_YELLOW = Color(0xFFFFC94D)

private fun DrawScope.heart(c: Offset, r: Float, color: Color, alpha: Float = 1f) {
    val path = Path().apply {
        moveTo(c.x, c.y + r * 0.9f)
        cubicTo(c.x - r * 1.6f, c.y - r * 0.2f, c.x - r * 0.7f, c.y - r * 1.3f, c.x, c.y - r * 0.45f)
        cubicTo(c.x + r * 0.7f, c.y - r * 1.3f, c.x + r * 1.6f, c.y - r * 0.2f, c.x, c.y + r * 0.9f)
        close()
    }
    drawPath(path, color, alpha = alpha)
}

private fun DrawScope.star4(c: Offset, r: Float, color: Color, alpha: Float = 1f) {
    val k = 0.28f
    val star = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r * k, c.y - r * k)
        lineTo(c.x + r, c.y)
        lineTo(c.x + r * k, c.y + r * k)
        lineTo(c.x, c.y + r)
        lineTo(c.x - r * k, c.y + r * k)
        lineTo(c.x - r, c.y)
        lineTo(c.x - r * k, c.y - r * k)
        close()
    }
    drawPath(star, color, alpha = alpha)
}

/**
 * [reactTick]: naikkan nilainya untuk membuat maskot bereaksi dari luar (mis. tugas dicentang); [reactBig] = muter (semua beres) bukan loncat.
 * [onTap]: dipanggil dengan kalimat yang diucapkan maskot saat diketuk, supaya pemanggil bisa menampilkannya di gelembung.
 */
@Composable
fun Mascot(
    mood: Int, th: CuteTheme, modifier: Modifier = Modifier,
    reactTick: Int = 0, reactBig: Boolean = false, onTap: (String) -> Unit = {}
) {
    val inf = rememberInfiniteTransition(label = "mascot")
    val bob by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val blink by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 3800
            0f at 0
            0f at 3560
            1f at 3680
            0f at 3800
        }),
        label = "blink"
    )
    val orbit by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "orbit")
    val scan by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "scan")
    val haptic = LocalHapticFeedback.current

    // ---- reaksi ----
    val prog = remember { Animatable(1f) }                    // 1 = tidak ada reaksi yang berjalan
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    val memo = remember { IntArray(3) }                       // [0] id terakhir, [1] jenis terakhir, [2] jumlah ketukan beruntun
    val lastTap = remember { LongArray(1) }

    fun start(kind: Int) { memo[0]++; memo[1] = kind; reaction = Reaction(memo[0], kind) }

    LaunchedEffect(reaction) {
        val r = reaction ?: return@LaunchedEffect
        prog.snapTo(0f)
        prog.animateTo(1f, tween(reactionMs(r.kind), easing = LinearEasing))
    }
    // naik dari luar; nilai awal diabaikan supaya tidak bereaksi sendiri saat layar dibuka lagi (mis. keluar dari kalender)
    val firstTick = remember { reactTick }
    LaunchedEffect(reactTick) { if (reactTick != firstTick) start(if (reactBig) R_SPIN else R_JUMP) }

    val onClick: () -> Unit = {
        val now = SystemClock.uptimeMillis()
        if (now - lastTap[0] > 1200L) memo[2] = 0
        lastTap[0] = now
        memo[2]++
        val busy = reaction != null && prog.value < 0.5f
        if (memo[2] >= 6) {
            memo[2] = 0
            start(R_DIZZY)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onTap(dizzyLines.random())
        } else if (!busy) {
            val kind = (memo[1] + 1 + Random.nextInt(3)) % 4      // selalu beda dari reaksi sebelumnya
            start(kind)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTap(tapLines[kind].random())
        }
    }

    Canvas(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Colek $MASCOT_NAME", onClick = onClick)
            .graphicsLayer {
                var ty = -bob * 5.dp.toPx()
                var sx = 1f; var sy = 1f; var rot = 0f
                val r = reaction
                val q = prog.value
                if (r != null && q < 1f) {
                    when (r.kind) {
                        R_JUMP, R_SPIN -> {
                            ty -= 4f * q * (1f - q) * 10.dp.toPx()                        // parabola loncat
                            val stretch = sin(q * PI.toFloat()) * 0.10f                    // memanjang saat di udara
                            val land = if (q > 0.85f) sin((q - 0.85f) / 0.15f * PI.toFloat()) * 0.12f else 0f   // gepeng saat mendarat
                            sy = 1f + stretch - land; sx = 1f - stretch * 0.6f + land
                            if (r.kind == R_SPIN) rot = FastOutSlowInEasing.transform(q) * 360f
                        }
                        R_WIGGLE -> rot = sin(q * PI.toFloat() * 6f) * 9f * (1f - q)
                        R_LOVE -> { val k = 1f + 0.06f * sin(q * PI.toFloat() * 2f); sx = k; sy = k }   // berdebar
                        R_DIZZY -> rot = sin(q * PI.toFloat() * 8f) * 14f * (1f - q * 0.5f)
                    }
                }
                translationY = ty; scaleX = sx; scaleY = sy; rotationZ = rot
                transformOrigin = TransformOrigin(0.5f, 0.92f)
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h * 0.58f
        val rx = w * 0.40f
        val ry = h * 0.34f
        val body = th.primary
        val bodyHi = lerp(body, Color.White, 0.40f)
        val bodyLo = lerp(body, NAVY, 0.34f)
        val open = 1f - blink * 0.92f
        val sw = 2.2.dp.toPx()
        val hair = 1.dp.toPx()
        val pi2 = 2f * PI.toFloat()

        val rc = reaction
        val q = prog.value
        val kind = if (rc != null && q < 1f) rc.kind else -1
        val happyEyes = kind == R_JUMP || kind == R_WIGGLE || kind == R_SPIN

        // ---- cincin orbit di leher (seperti Saturnus): separuh belakang digambar di sini, separuh depan setelah badan ----
        val ringCx = cx
        val ringCy = cy + ry * 0.62f
        val ringRx = rx * 1.16f
        val ringRy = ry * 0.30f
        val ringTL = Offset(ringCx - ringRx, ringCy - ringRy)
        val ringSz = Size(ringRx * 2f, ringRy * 2f)
        val ringCol = lerp(body, Color.White, 0.30f)
        val ang = orbit * pi2
        val dotR = w * 0.022f
        val dotFront = sin(ang) >= 0f
        val dot = Offset(ringCx + ringRx * cos(ang), ringCy + ringRy * sin(ang))
        rotate(-9f, Offset(ringCx, ringCy)) {
            drawOval(ringCol.copy(alpha = 0.45f), ringTL, ringSz, style = Stroke(1.5.dp.toPx()))
            if (!dotFront) { drawCircle(NEON.copy(alpha = 0.30f), dotR * 2.2f, dot); drawCircle(NEON, dotR, dot) }
        }

        // ---- telinga antena ----
        for (sign in SIGNS) {
            val ear = Path().apply {
                moveTo(cx + sign * rx * 0.88f, cy - ry * 0.45f)
                lineTo(cx + sign * rx * 0.78f, h * 0.08f)
                lineTo(cx + sign * rx * 0.12f, cy - ry * 0.98f)
                close()
            }
            drawPath(ear, body)
            drawPath(ear, body, style = Stroke(width = 8.dp.toPx(), join = StrokeJoin.Round))
            val inner = Path().apply {
                moveTo(cx + sign * rx * 0.76f, cy - ry * 0.62f)
                lineTo(cx + sign * rx * 0.72f, h * 0.18f)
                lineTo(cx + sign * rx * 0.36f, cy - ry * 0.88f)
                close()
            }
            drawPath(inner, th.accent)
            // ujung antena berdenyut
            val tip = Offset(cx + sign * rx * 0.78f, h * 0.085f)
            val pulse = 0.8f + 0.5f * bob
            drawCircle(NEON.copy(alpha = 0.28f), w * 0.034f * pulse, tip)
            drawCircle(NEON, w * 0.017f, tip)
        }

        // ---- kepala: gradasi + pinggiran terang ----
        val headTL = Offset(cx - rx, cy - ry)
        val headSz = Size(rx * 2f, ry * 2f)
        val headR = CornerRadius(ry * 0.92f, ry * 0.92f)
        drawRoundRect(Brush.verticalGradient(listOf(bodyHi, body, bodyLo), startY = cy - ry, endY = cy + ry), headTL, headSz, headR)
        drawRoundRect(Color.White.copy(alpha = 0.35f), headTL, headSz, headR, style = Stroke(hair))
        drawOval(Color.White.copy(alpha = 0.22f), Offset(cx - rx * 0.72f, cy - ry * 0.90f), Size(rx * 0.55f, ry * 0.20f))

        // separuh depan cincin orbit
        rotate(-9f, Offset(ringCx, ringCy)) {
            drawArc(ringCol, 0f, 180f, false, ringTL, ringSz, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            if (dotFront) { drawCircle(NEON.copy(alpha = 0.30f), dotR * 2.2f, dot); drawCircle(NEON, dotR, dot) }
        }

        // permata di dahi
        star4(Offset(cx, cy - ry * 0.78f), w * 0.035f * (0.8f + 0.4f * bob), NEON)

        // ---- layar visor ----
        val vh = ry * 1.08f
        val vw = rx * 1.60f
        val vx = cx - vw / 2f
        val vy = cy - ry * 0.50f
        drawRoundRect(VISOR, Offset(vx, vy), Size(vw, vh), CornerRadius(vh * 0.5f, vh * 0.5f))
        drawRoundRect(NEON.copy(alpha = 0.55f), Offset(vx, vy), Size(vw, vh), CornerRadius(vh * 0.5f, vh * 0.5f), style = Stroke(hair))
        // garis pindai menyapu layar
        val scanY = vy + vh * (0.14f + 0.72f * scan)
        drawLine(NEON.copy(alpha = 0.16f), Offset(vx + vh * 0.35f, scanY), Offset(vx + vw - vh * 0.35f, scanY), 1.5.dp.toPx())

        // ---- mata LED ----
        val ex = rx * 0.42f
        val ey = cy - ry * 0.08f
        val er = w * 0.05f
        for (s in SIGNS) {
            val ecx = cx + s * ex
            when {
                kind == R_LOVE -> heart(Offset(ecx, ey), er * 1.5f, HEART_PINK)
                kind == R_DIZZY -> {
                    val d = er * 0.9f
                    drawLine(NEON, Offset(ecx - d, ey - d), Offset(ecx + d, ey + d), sw, StrokeCap.Round)
                    drawLine(NEON, Offset(ecx - d, ey + d), Offset(ecx + d, ey - d), sw, StrokeCap.Round)
                }
                happyEyes || mood == 3 -> {
                    val tl = Offset(ecx - er, ey - er * 0.6f)
                    val sz = Size(er * 2f, er * 2f)
                    drawArc(NEON.copy(alpha = 0.25f), 200f, 140f, false, tl, sz, style = Stroke(sw * 2.4f, cap = StrokeCap.Round))
                    drawArc(NEON, 200f, 140f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                mood == 0 -> drawLine(NEON.copy(alpha = 0.6f), Offset(ecx - er, ey), Offset(ecx + er, ey), sw, StrokeCap.Round)
                else -> {
                    val eh = er * 2.5f * open
                    drawOval(NEON.copy(alpha = 0.22f), Offset(ecx - er * 1.45f, ey - eh * 0.5f - er * 0.45f), Size(er * 2.9f, eh + er * 0.9f))
                    drawOval(NEON, Offset(ecx - er * 0.9f, ey - eh / 2f), Size(er * 1.8f, eh))
                    if (open > 0.6f) drawCircle(Color.White, er * 0.28f, Offset(ecx + er * 0.25f, ey - eh * 0.25f))
                }
            }
        }

        // ---- pipi neon merah muda (makin terang saat tersipu / kegelian) ----
        val blushA = if (kind == R_LOVE || kind == R_WIGGLE) 0.9f else 0.5f
        for (s in SIGNS) {
            drawOval(
                HEART_PINK.copy(alpha = blushA),
                Offset(cx + s * rx * 0.60f - w * 0.035f, cy + ry * 0.20f),
                Size(w * 0.07f, w * 0.04f)
            )
        }

        // ---- mulut "ω" LED ----
        val my = cy + ry * 0.26f
        val mouth = when (kind) { R_DIZZY -> 4; -1 -> mood; else -> 3 }
        when (mouth) {
            0 -> drawCircle(NEON.copy(alpha = 0.7f), w * 0.014f, Offset(cx, my + w * 0.012f))
            1, 2 -> {
                val mw = if (mouth == 1) w * 0.04f else w * 0.055f
                for (s in SIGNS) {
                    drawArc(NEON, 10f, 160f, false, Offset(cx + s * mw / 2f - mw / 2f, my - mw / 2f), Size(mw, mw), style = Stroke(sw * 0.85f, cap = StrokeCap.Round))
                }
            }
            4 -> drawOval(NEON, Offset(cx - w * 0.035f, my), Size(w * 0.07f, w * 0.045f), style = Stroke(sw * 0.8f))
            else -> {
                val mw = w * 0.13f
                drawArc(NEON, 0f, 180f, true, Offset(cx - mw / 2f, my - mw * 0.25f), Size(mw, mw))
            }
        }

        // hati melayang di atas kepala
        if (kind == R_LOVE) {
            for (i in 0..2) {
                val lp = (q * 1.6f - i * 0.25f).coerceIn(0f, 1f)
                if (lp > 0f && lp < 1f) {
                    val hx = cx + (i - 1) * w * 0.17f + sin(lp * PI.toFloat() * 2f + i) * w * 0.02f
                    val hy = h * 0.12f - lp * h * 0.22f
                    heart(Offset(hx, hy), w * 0.045f, HEART_PINK, alpha = sin(lp * PI.toFloat()))
                }
            }
        }

        // bintang berputar di atas kepala saat pusing
        if (kind == R_DIZZY) {
            val a0 = q * 2f * PI.toFloat() * 3f
            val fade = if (q > 0.85f) (1f - q) / 0.15f else 1f
            for (i in 0..2) {
                val a = a0 + i * (2f * PI.toFloat() / 3f)
                star4(Offset(cx + cos(a) * w * 0.26f, h * 0.10f + sin(a) * h * 0.04f), w * 0.045f, STAR_YELLOW, fade)
            }
        }

        // kilau hologram saat semua tugas selesai
        if (mood == 3) {
            val tw = 0.6f + 0.4f * bob
            star4(Offset(w * 0.08f, h * 0.30f), w * 0.07f * tw, NEON)
            star4(Offset(w * 0.93f, h * 0.24f), w * 0.06f * (1.6f - tw), bodyHi)
            star4(Offset(w * 0.90f, h * 0.80f), w * 0.05f * tw, NEON)
        }
    }
}

/** Gelembung ucapan maskot; [name] (opsional) tampil kecil di atas teks sebagai nama pembicara. */
@Composable
fun Bubble(text: String, modifier: Modifier = Modifier, name: String? = null) {
    Surface(
        modifier,
        shape = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (name != null) Text(
                "✦ ${name.uppercase()}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.primary
            )
            Crossfade(text, label = "bubble") {
                Text(it, Modifier.padding(top = 2.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
