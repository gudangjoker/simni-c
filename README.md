# SIMNI-C (Chatrok)

Aplikasi Administrasi Kelas dan Perpesanan Aman SIMNI-C (Android Native).

## Prasyarat
- [Android Studio](https://developer.android.com/studio)
- JDK 21
- Android SDK (API 34 / 36)

## Menjalankan Proyek Secara Lokal

1. Buka **Android Studio**.
2. Pilih **Open** dan arahkan ke direktori proyek ini (`Chatrok`).
3. Tunggu proses sinkronisasi Gradle (Gradle Sync) selesai.
4. Buat berkas `.env` di direktori root proyek (salin dari `.env.example`):
   ```bash
   cp .env.example .env
   ```
5. Sesuaikan konfigurasi pada `.env` bila diperlukan:
   ```env
   WORKER_BASE_URL=https://chatrok-media-gateway.2ndgoal.workers.dev
   ```
6. Jalankan aplikasi pada emulator atau perangkat fisik Android melalui tombol **Run** di Android Studio atau via terminal:
   ```bash
   ./gradlew assembleDebug
   ```

## Struktur Proyek
- `app/`: Modul utama aplikasi Android (Kotlin, Jetpack Compose, Room, Hilt, Firebase, WebRTC).
- `edge/`: Layanan Cloudflare Workers untuk media gateway dan signaling.
- `gradle/`: Konfigurasi build dan dependency catalog (`libs.versions.toml`).
