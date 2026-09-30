package com.cutetodo.app

/** Pilihan suara pengingat per tugas. key "" = bawaan, "custom" = audio yang dipilih sendiri dari perangkat. */
data class SoundOpt(val key: String, val label: String, val res: Int)

object Sounds {
    const val CUSTOM = "custom"

    val all = listOf(
        SoundOpt("", "Alarm imut", R.raw.alarm_cute),
        SoundOpt("bell", "Lonceng mungil", R.raw.snd_bell),
        SoundOpt("bubble", "Gelembung", R.raw.snd_bubble),
        SoundOpt("boing", "Boing", R.raw.snd_boing),
        SoundOpt("sparkle", "Bintang", R.raw.snd_sparkle),
        SoundOpt("chick", "Anak ayam", R.raw.snd_chick),
        SoundOpt("musicbox", "Kotak musik", R.raw.snd_musicbox),
        SoundOpt("horn", "Terompet mainan", R.raw.snd_horn),
        SoundOpt("whistle", "Peluit meluncur", R.raw.snd_whistle)
    )

    fun find(key: String): SoundOpt = all.firstOrNull { it.key == key } ?: all[0]
}
