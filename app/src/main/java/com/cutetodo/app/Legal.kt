package com.cutetodo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/** Alamat halaman resmi LiaDo (Beranda, Kebijakan Privasi, Ketentuan Layanan). */
object Legal {
    private const val BASE = "https://liado-app.netlify.app"
    const val HOME = "$BASE/"
    const val PRIVACY = "$BASE/privacy"
    const val TERMS = "$BASE/terms"

    /**
     * Membuka tautan di Chrome Custom Tab (tetap terasa di dalam aplikasi, ada tombol tutup),
     * dengan warna bilah mengikuti tema aplikasi. Jika tidak ada peramban yang mendukung,
     * jatuh ke Intent biasa; jika tetap gagal, tampilkan pesan.
     */
    fun open(ctx: Context, url: String, toolbarColor: Int? = null) {
        val uri = Uri.parse(url)
        try {
            val b = CustomTabsIntent.Builder().setShowTitle(true).setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            toolbarColor?.let {
                b.setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(it).build())
            }
            b.build().launchUrl(ctx, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(ctx, "Tidak bisa membuka tautan", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
