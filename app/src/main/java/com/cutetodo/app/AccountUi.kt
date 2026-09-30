package com.cutetodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Date

private val Amber = Color(0xFFE59A00)

/** Popup akun Google: belum masuk -> ajakan + manfaat; sudah masuk -> profil, status, sinkronkan, keluar. */
@Composable
fun AccountDialog(
    th: CuteTheme, st: SyncState,
    onSignIn: () -> Unit, onSync: () -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val needsLogin = !st.on || st.needsLogin
    val initial = (st.name ?: st.email)?.trim()?.firstOrNull()?.uppercase() ?: "L"

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surface, tonalElevation = 6.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Kepala: avatar / ikon
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(th.accent.copy(alpha = if (th.dark) 0.5f else 1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (st.on) Text(initial, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = th.primary)
                    else Icon(Icons.Rounded.CloudUpload, null, tint = th.primary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(14.dp))

                if (!st.on) {
                    Text("Masuk dengan Google", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Simpan tugasmu dengan aman dan buka di HP mana pun.",
                        fontSize = 14.sp, textAlign = TextAlign.Center, color = cs.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Notice(
                        "Kamu belum masuk",
                        "Tugas, foto, dan catatan suara hanya tersimpan di HP ini. Jika aplikasi terhapus atau HP hilang, datamu ikut hilang."
                    )
                    Spacer(Modifier.height(16.dp))
                    Benefit(Icons.Rounded.Lock, "Tersimpan aman", "Data ada di Google Drive milikmu sendiri.", th)
                    Benefit(Icons.Rounded.Devices, "Muncul di HP lain", "Masuk dengan akun yang sama, semua tugasmu ikut.", th)
                    Benefit(Icons.Rounded.VisibilityOff, "Tetap privat", "Hanya folder khusus LiaDo. File Drive lain tidak bisa dilihat.", th)
                    Spacer(Modifier.height(20.dp))
                    PrimaryBtn("Lanjutkan dengan Google", th, onSignIn)
                } else {
                    Text(st.name ?: "Akun Google", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (st.email != null) {
                        Text(st.email, fontSize = 14.sp, textAlign = TextAlign.Center, color = cs.onSurface.copy(alpha = 0.7f))
                    }
                    Spacer(Modifier.height(16.dp))
                    if (st.needsLogin) {
                        Notice("Sesi Google berakhir", "Masuk ulang supaya sinkronisasi bisa lanjut. Datamu di HP ini tetap aman.")
                    } else {
                        val status = when {
                            st.busy -> "Menyinkronkan…"
                            st.error != null -> st.error
                            st.last > 0 -> "Terakhir tersinkron: ${fmt.format(Date(st.last))}"
                            else -> "Belum pernah tersinkron"
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (st.error != null) cs.error.copy(alpha = 0.12f) else th.accent.copy(alpha = if (th.dark) 0.35f else 0.55f))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (st.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = th.primary)
                            else Icon(
                                if (st.error != null) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
                                tint = if (st.error != null) cs.error else th.primary, modifier = Modifier.size(20.dp)
                            )
                            Text(status ?: "", fontSize = 13.sp, color = if (st.error != null) cs.error else cs.onSurface)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Perubahan tersinkron otomatis saat ada internet dan setiap aplikasi dibuka.",
                            fontSize = 12.sp, textAlign = TextAlign.Center, color = cs.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    if (st.needsLogin) PrimaryBtn("Masuk ulang", th, onSignIn)
                    else PrimaryBtn("Sinkronkan sekarang", th, onSync, enabled = !st.busy)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        Text("Keluar", color = cs.error, fontWeight = FontWeight.SemiBold)
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Tutup", color = cs.onSurface.copy(alpha = 0.7f))
                }
                // Tautan resmi (dibutuhkan untuk alur login Google)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { Legal.open(ctx, Legal.PRIVACY, th.primary.toArgb()) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Kebijakan Privasi", fontSize = 12.sp, color = th.primary)
                    }
                    Text("•", fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.4f))
                    TextButton(onClick = { Legal.open(ctx, Legal.TERMS, th.primary.toArgb()) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Ketentuan Layanan", fontSize = 12.sp, color = th.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryBtn(text: String, th: CuteTheme, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = th.primary, contentColor = th.onPrimary)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, desc: String, th: CuteTheme) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(th.accent.copy(alpha = if (th.dark) 0.5f else 1f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = th.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun Notice(title: String, text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Amber.copy(alpha = 0.16f)).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
    }
}

/** Pemberitahuan di layar utama untuk pengguna yang belum masuk Google (atau sesinya berakhir). Tidak bisa ditutup. */
@Composable
fun LoginNudge(th: CuteTheme, expired: Boolean, onLogin: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(th.accent.copy(alpha = if (th.dark) 0.35f else 0.6f))
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (expired) Icons.Rounded.WarningAmber else Icons.Rounded.CloudUpload, null, tint = th.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (expired) "Sesi Google berakhir" else "Kamu belum masuk",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink
            )
            Text(
                if (expired) "Masuk ulang supaya sinkronisasi lanjut."
                else "Tugas, foto, dan catatan suara hanya ada di HP ini. Masuk dengan Google agar aman dan bisa dibuka di HP lain.",
                fontSize = 12.sp, color = ink.copy(alpha = 0.75f), lineHeight = 16.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onLogin,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = th.primary, contentColor = th.onPrimary)
        ) { Text(if (expired) "Masuk ulang" else "Masuk", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}
