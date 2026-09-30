# Setup Login Google & Sinkronisasi (LiaDo)

## 1. Dependency (build.gradle modul app)
implementation("com.google.android.gms:play-services-auth:21.3.0")

## 2. Google Cloud Console (sekali saja, gratis)
1. console.cloud.google.com -> buat/pilih project.
2. APIs & Services -> Library -> aktifkan "Google Drive API".
3. OAuth consent screen -> isi nama app & email. Tambahkan scope
   ".../auth/drive.appdata". Selama status "Testing", daftarkan email
   penguji di "Test users". (Ubah ke "In production" agar semua orang bisa masuk.)
4. Credentials -> Create credentials -> OAuth client ID -> tipe **Android**:
   - Package name: com.cutetodo.app
   - SHA-1: dari keystore yang dipakai menandatangani APK
     (debug: `./gradlew signingReport`; rilis: keystore rilis kamu).
   Buat satu client per keystore (debug & rilis dipisah).
Tidak perlu google-services.json dan tidak perlu menaruh client ID di kode.

## 3. Cara kerja
- Data ada di folder tersembunyi Drive milik pengguna (appDataFolder).
- Tiap tugas punya uid + updatedAt; versi terbaru menang. Penghapusan dicatat (tombstone, 90 hari).
- Sinkron otomatis: saat app dibuka, dan 4 detik setelah ada perubahan.
