package com.cutetodo.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val SkyTop = Color(0xFFD3E8FF)
private val SkyMid = Color(0xFFE8F3FF)
private val SkyBottom = Color(0xFFF6FAFF)
private val Blue = Color(0xFF5B8DEF)
private val BlueSoft = Color(0xFF9CC5F7)
private val Ink = Color(0xFF24365E)

/** Gelembung latar: x & ukuran dalam pecahan/dp, [speed] = berapa putaran naik per siklus (bulat supaya loop mulus). */
private class Bub(val x: Float, val r: Float, val phase: Float, val speed: Int)

private val bubbles = listOf(
    Bub(0.10f, 14f, 0.05f, 1), Bub(0.24f, 8f, 0.55f, 2), Bub(0.38f, 18f, 0.30f, 1),
    Bub(0.55f, 10f, 0.80f, 2), Bub(0.70f, 16f, 0.15f, 1), Bub(0.86f, 9f, 0.65f, 2),
    Bub(0.92f, 20f, 0.42f, 1), Bub(0.03f, 11f, 0.90f, 2), Bub(0.47f, 7f, 0.20f, 3),
    Bub(0.78f, 12f, 0.95f, 3)
)

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticBezierTo(c.x, c.y, c.x + r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, color)
}

/**
 * Halaman pembuka: logo memantul masuk, gelembung & bintang melayang, titik loading melompat,
 * lalu menampilkan kredit pembuat. Ketuk layar untuk melewati.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    // bar sistem ikut biru muda selama intro
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = SkyTop.toArgb()
        window.navigationBarColor = SkyBottom.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    val pop = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    var showCredit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
        launch { delay(700); showCredit = true }
        progress.animateTo(1f, tween(2800, easing = FastOutSlowInEasing))
        delay(300)
        onFinished()
    }

    val inf = rememberInfiniteTransition(label = "intro")
    val loop by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "loop")
    val bob by inf.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val glow by inf.animateFloat(
        0.85f, 1.15f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val twinkle by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "twinkle")
    val dots by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "dots")

    val creditAlpha by animateFloatAsState(if (showCredit) 1f else 0f, tween(700), label = "credit")
    val density = LocalDensity.current.density

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyBottom)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() }
    ) {
        // gelembung yang naik pelan-pelan
        Canvas(Modifier.fillMaxSize()) {
            bubbles.forEach { b ->
                val t = (loop * b.speed + b.phase) % 1f
                val r = b.r * density
                val cx = size.width * b.x + sin(t * 2f * PI.toFloat()) * 12f * density
                val cy = size.height + r - t * (size.height + 2 * r)
                drawCircle(Color.White.copy(alpha = 0.55f), r, Offset(cx, cy))
                drawCircle(BlueSoft.copy(alpha = 0.55f), r, Offset(cx, cy), style = Stroke(1.5f * density))
                drawCircle(Color.White.copy(alpha = 0.9f), r * 0.22f, Offset(cx - r * 0.35f, cy - r * 0.35f))
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // logo + cahaya lembut + bintang berkelap-kelip
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = center
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0f)),
                            center = c, radius = size.minDimension / 2f * glow
                        ),
                        radius = size.minDimension / 2f * glow, center = c
                    )
                    val s1 = 0.5f + 0.5f * sin(twinkle * 2f * PI.toFloat())
                    val s2 = 0.5f + 0.5f * sin((twinkle + 0.5f) * 2f * PI.toFloat())
                    val s3 = 0.5f + 0.5f * sin((twinkle + 0.25f) * 2f * PI.toFloat())
                    sparkle(Offset(size.width * 0.92f, size.height * 0.18f), 16f * density * (0.5f + s1 * 0.6f), Blue.copy(alpha = 0.35f + 0.65f * s1))
                    sparkle(Offset(size.width * 0.06f, size.height * 0.30f), 11f * density * (0.5f + s2 * 0.6f), Color.White.copy(alpha = 0.4f + 0.6f * s2))
                    sparkle(Offset(size.width * 0.86f, size.height * 0.86f), 12f * density * (0.5f + s3 * 0.6f), BlueSoft.copy(alpha = 0.4f + 0.6f * s3))
                    sparkle(Offset(size.width * 0.12f, size.height * 0.82f), 9f * density * (0.5f + s1 * 0.6f), Color.White.copy(alpha = 0.4f + 0.6f * s1))
                }
                Image(
                    painterResource(R.drawable.logo_app), "Logo LiaDo",
                    Modifier.size(170.dp).graphicsLayer {
                        val s = 0.2f + 0.8f * pop.value
                        scaleX = s; scaleY = s
                        translationY = bob * 8f * density
                        rotationZ = bob * 3f
                        alpha = pop.value.coerceIn(0f, 1f)
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text("LiaDo", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("Teman kecil untuk harimu", fontSize = 15.sp, color = Ink.copy(alpha = 0.65f), textAlign = TextAlign.Center)

            Spacer(Modifier.height(32.dp))
            // tiga titik melompat
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(26.dp)) {
                repeat(3) { i ->
                    val hop = sin(((dots + i * 0.18f) % 1f) * PI.toFloat()).coerceAtLeast(0f)
                    Box(
                        Modifier.size(12.dp).graphicsLayer { translationY = -hop * 12f * density }
                            .clip(CircleShape).background(if (i == 1) Blue else BlueSoft)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.width(190.dp).height(10.dp).clip(CircleShape),
                color = Blue, trackColor = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    progress.value < 0.35f -> "Menyiapkan tugas-tugas lucu…"
                    progress.value < 0.75f -> "Menaburkan bintang…"
                    else -> "Hampir siap!"
                },
                fontSize = 13.sp, color = Ink.copy(alpha = 0.6f)
            )
        }

        // kredit pembuat
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).graphicsLayer { alpha = creditAlpha; translationY = (1f - creditAlpha) * 16f * density },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Created by", fontSize = 12.sp, color = Ink.copy(alpha = 0.55f))
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = Blue, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Bobby Anggara", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.Favorite, null, tint = Blue, modifier = Modifier.size(14.dp))
            }
        }
    }
}
