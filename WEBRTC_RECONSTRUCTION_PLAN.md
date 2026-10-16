# 📞 RENCANA REKONSTRUKSI WEBRTC NATIVE (MODEL 1) - SIMNI-C

Dokumen ini berisi cetak biru arsitektur, analisis kesenjangan, dan rencana teknis pelaksanaan rekonstruksi modul panggilan suara dan video berbasis **WebRTC P2P Native + Firestore Signaling (Model 1)** untuk aplikasi **SIMNI-C**.

---

## 1. 🎯 Latar Belakang & Tujuan
Saat ini antarmuka UI panggilan suara dan video di [`CallScreen.kt`](app/src/main/java/com/chatrok/app/feature/call/CallScreen.kt) sudah dirancang dengan sangat baik, namun mesin komunikasi bawahnya masih berupa simulasi / mockup teks. 

Tujuan rekonstruksi ini adalah:
1. **Mengaktifkan Panggilan Riil (Real Voice & Video Calls)**: Pengguna dapat melakukan panggilan suara dan video berlatensi rendah secara langsung.
2. **100% End-to-End Private (P2P)**: Aliran media audio/video mengalir langsung antar perangkat pengguna tanpa melalui server pihak ketiga.
3. **Zero Operational Cost**: Menggunakan infrastruktur Firebase Firestore yang sudah ada untuk sinyal (signaling) dan server STUN gratis Google untuk NAT discovery.
4. **Ukuran Aplikasi Tetap Ramping**: Mengoptimalkan library WebRTC dengan ABI filter (`arm64-v8a`, `armeabi-v7a`) agar ukuran APK tetap terjaga di kisaran ~12–18 MB.

---

## 2. 🔍 Analisis Kesenjangan (Gap Analysis)

Berdasarkan audit total source code WebRTC:

| Komponen | Status Saat Ini | Dampak | Solusi Rekonstruksi |
| :--- | :--- | :--- | :--- |
| **Firestore Signaling Path** | Mengakses `/calls/{callId}` yang diblokir oleh `firestore.rules` (`allow read, write: if false`). | `PERMISSION_DENIED` saat panggil. | Alihkan target ke `/callSessions/{callId}` yang sudah diotorisasi di rules. |
| **Skema Data Sinyal** | Atribut data class `SignalingCallSession` tidak cocok dengan validasi rules (`callerId` vs `callerUid`, `status` vs `state`, dll). | Firestore menolak dokumen create/update. | Sesuaikan field skema 100% identik dengan aturan di `firestore.rules` (baris 411-517). |
| **SDP Offer/Answer** | Masih string dummy hardcoded `"OFFER_INIT_SDP"` dan `"SDP_ANSWER_LOCAL"`. | Tidak ada negosiasi codec dan media track. | Dihasilkan secara dinamis dari `PeerConnection.createOffer()` dan `createAnswer()`. |
| **ICE Candidates** | Ditampung di subkoleksi tetapi tidak pernah diteruskan ke WebRTC engine. | Jalur transmisi P2P tidak pernah terbentuk. | Observer Firestore ICE Candidate langsung diumpankan ke `peerConnection.addIceCandidate()`. |
| **WebRTC Media Engine** | Dependency WebRTC native belum diaktifkan; tidak ada `PeerConnectionFactory`. | Tidak ada audio/video yang ditransmisikan. | Pasang wrapper `WebRtcEngine` terisolasi dengan EGL context dan hardware acceleration. |
| **Audio Hardware Routing** | Tidak ada `AudioManager.MODE_IN_COMMUNICATION`, AEC, maupun Proximity Sensor. | Gema suara (echo), suara kecil, layar tidak mati saat ditempelkan ke telinga. | Buat `CallAudioManager` dan `ProximitySensorManager`. |
| **Background Lifecycle** | Belum ada `ForegroundService`. | Panggilan terputus saat pengguna minimize aplikasi atau layar mati. | Implementasikan `CallService` tipe `phoneCall` dengan notifikasi panggilan aktif. |
| **UI Video Rendering** | Kontainer video di Compose masih berupa `Box` warna statis dengan avatar. | Gambar kamera lokal dan remote tidak muncul. | Ganti dengan `AndroidView` yang membungkus `SurfaceViewRenderer` native. |

---

## 3. 🏛️ Arsitektur Target (Model 1: P2P + Firestore Signaling)

```
[ PENGGUNA A (Caller) ]                                  [ PENGGUNA B (Callee) ]
          │                                                         │
          ├─────── 1. Buat Call Session (/callSessions) ───────────>│ (Notifikasi FCM &
          │           State: "ringing", Offer SDP                   │  Ring Screen)
          │                                                         │
          │<────── 2. Terima Panggilan (Update Session) ────────────┤
          │           State: "accepted", Answer SDP                 │
          │                                                         │
          ├─────── 3. Pertukaran ICE Candidates (Firestore) ───────>│
          │<──────    Subkoleksi: /callSessions/{id}/candidates ────┤
          │                                                         │
          ▼                                                         ▼
    [ WebRTC STUN ]                                           [ WebRTC STUN ]
(stun.l.google.com:19302)                                 (stun.l.google.com:19302)
          │                                                         │
          └───────────────► [ KONEKSI P2P LANGSUNG ] ◄──────────────┘
                             Media Stream Terenkripsi
                             (Audio OPUS + Video VP8/H264)
```

---

## 4. 📋 Rencana Implementasi Bertahap (Phased Roadmap)

### Fase 1: Sinkronisasi Skema Data & Firestore Signaling
1. **Perbaikan Model Data**:
   * Ubah model sinyal agar mematuhi aturan ketat `firestore.rules`:
     ```kotlin
     data class CallSessionFirestore(
         val schemaVersion: Int = 1,
         val callerUid: String = "",
         val calleeUid: String = "",
         val conversationId: String = "",
         val type: String = "voice", // "voice" | "video"
         val state: String = "ringing", // "ringing" -> "accepted" -> "connecting" -> "connected" -> "ended"
         val callerName: String? = null,
         val offer: Map<String, String>? = null, // {"type": "offer", "sdp": "..."}
         val answer: Map<String, String>? = null, // {"type": "answer", "sdp": "..."}
         val createdAt: Any? = null,
         val expiresAt: Long = 0L,
         val reason: String? = null
     )
     ```
2. **Perbaikan Koleksi & Subkoleksi**:
   * Arahkan semua query dan listener dari `/calls` ke `/callSessions`.
   * Simpan kandidat ICE di subkoleksi `/callSessions/{callId}/candidates` dengan field:
     `{ candidate, sdpMid, sdpMLineIndex, senderUid, createdAt }`.
3. **Pembersihan Sesi Otomatis**:
   * Set waktu kedaluwarsa `expiresAt = System.currentTimeMillis() + 60_000` (1 menit) jika panggilan tidak diangkat agar panggilan ringing otomatis timeout menjadi "missed".

---

### Fase 2: Native WebRTC Core Engine (`WebRtcClient`)
1. **Inisialisasi Factory**:
   * Buat kelas terisolasi `WebRtcClient.kt` di package `com.chatrok.app.core.webrtc`.
   * Konfigurasi `PeerConnectionFactory`:
     * Video Decoder Factory: `DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)`
     * Video Encoder Factory: `DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)`
     * Audio Device Module dengan Acoustic Echo Canceler (AEC) dan Noise Suppressor (NS).
2. **Kamera & Media Stream**:
   * Integrasikan `Camera2Enumerator` untuk menangkap video kamera depan dan belakang.
   * Buat `LocalAudioTrack` dan `LocalVideoTrack`.
3. **Koneksi Peer & ICE Server**:
   * Konfigurasi STUN server standar:
     * `stun:stun.l.google.com:19302`
     * `stun:stun1.l.google.com:19302`
   * (Opsional Fallback): Dukungan konfigurasi TURN relay gratis/publik untuk koneksi di jaringan seluler dengan simetrik NAT ketat.
4. **Observer Integrasi Sinyal**:
   * Saat `onIceCandidate` tertrigger di WebRTC native, otomatis simpan ke Firestore subkoleksi `candidates`.
   * Listener kandidat Firestore lawan otomatis dipanggilkan `peerConnection.addIceCandidate(...)`.

---

### Fase 3: Audio Hardware Controller & Sensor
1. **`CallAudioManager.kt`**:
   * Mengatur `AudioManager.mode = AudioManager.MODE_IN_COMMUNICATION`.
   * Mengatur `isSpeakerphoneOn = true/false` sesuai pilihan user pada tombol speaker.
   * Permintaan fokus audio (`AudioFocusRequest`) agar audio aplikasi lain (musik, YouTube) berhenti saat panggilan berlangsung.
2. **`ProximitySensorManager.kt`**:
   * Memanfaatkan sensor jarak (`Sensor.TYPE_PROXIMITY`) dan `PROXIMITY_SCREEN_OFF_WAKE_LOCK`.
   * Layar otomatis mati saat HP ditempelkan ke telinga dan menyala kembali saat dijauhkan.

---

### Fase 4: Android Telecom / Foreground Service (`CallService`)
1. **Background Persistence**:
   * Buat service `CallService : Service()` terdaftar dengan `android:foregroundServiceType="phoneCall|microphone|camera"`.
   * Menampilkan notifikasi persisten bertipe *Ongoing Call* dengan timer durasi berjalan dan tombol aksi "Tutup Panggilan".
   * Mencegah OS Android mematikan proses audio saat layar terkunci atau saat user membuka aplikasi lain.
2. **Incoming Call Notifier**:
   * Di `ChatrokFirebaseMessagingService.kt`, buat `PendingIntent` tipe `fullScreenIntent` pada saluran notifikasi `chatrok-calls` agar HP berdering dan memunculkan layar penerimaan panggilan saat terkunci.

---

### Fase 5: Jetpack Compose Video Renderer & UI Wiring
1. **SurfaceViewRenderer di Jetpack Compose**:
   * Di `CallScreen.kt`, pasang komponen Compose `AndroidView`:
     ```kotlin
     // Video Remote (Fullscreen)
     AndroidView(
         factory = { ctx ->
             SurfaceViewRenderer(ctx).apply {
                 init(eglContext, null)
                 setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                 setEnableHardwareScaler(true)
                 webRtcClient.attachRemoteRenderer(this)
             }
         },
         modifier = Modifier.fillMaxSize()
     )
     ```
     ```kotlin
     // Video Lokal (PiP Sudut Kanan Atas)
     AndroidView(
         factory = { ctx ->
             SurfaceViewRenderer(ctx).apply {
                 init(eglContext, null)
                 setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                 setMirror(controls.isFrontCamera)
                 webRtcClient.attachLocalRenderer(this)
             }
         },
         modifier = Modifier.fillMaxSize()
     )
     ```
2. **Aktivasi Tombol Panggilan di Chat**:
   * Di `ChatScreen.kt`, ganti blok Toast "Fitur ditangguhkan" dengan pemanggilan fungsi `chatViewModel.startCall(CallType.VOICE)` dan `chatViewModel.startCall(CallType.VIDEO)`.
3. **Integrasi Profil Pengguna**:
   * Di `UserProfileScreen.kt`, pastikan tombol telepon dan video langsung memanggil `startCall` ke user target (bukan `simulateIncomingCall`).

---

## 5. 🛡️ Keamanan & Kepatuhan Izin Android

Izin yang dipastikan tersedia di `AndroidManifest.xml`:
* `android.permission.RECORD_AUDIO` *(Sudah ada)*
* `android.permission.CAMERA` *(Sudah ada)*
* `android.permission.MODIFY_AUDIO_SETTINGS` *(Sudah ada)*
* `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_PHONE_CALL` *(Perlu ditambahkan untuk Android 14+)*
* `android.permission.BLUETOOTH_CONNECT` *(Perlu ditambahkan untuk audio headset nirkabel di Android 12+)*
* `android.permission.WAKE_LOCK` *(Perlu ditambahkan untuk menjaga CPU tetap aktif saat audio berlangsung)*

---

## 6. 🧪 Rencana Verifikasi & Uji Mutu

1. **Tes Sinyal Firestore**:
   * Pengujian pertukaran dokumen SDP offer, SDP answer, dan ICE candidate pada koleksi `/callSessions/{callId}` tanpa error permission.
2. **Uji Audio P2P Lokal**:
   * Pengujian dua perangkat di jaringan Wi-Fi lokal untuk memastikan suara dua arah jernih tanpa delay atau feedback loop (echo).
3. **Uji Lintas Jaringan Seluler (NAT Traversal)**:
   * Pengujian perangkat 1 di jaringan Wi-Fi dan perangkat 2 di jaringan data seluler (4G/5G) untuk memvalidasi keberhasilan STUN/TURN connection.
4. **Uji Pengalihan Kamera & Mic**:
   * Toggle mute suara, toggle kamera mati/nyala, dan flip kamera depan-belakang berjalan mulus tanpa freeze.
5. **Uji Ketahanan Latar Belakang (Backgrounding)**:
   * Menekan tombol Home saat panggilan suara aktif; audio tetap berjalan jernih dan notifikasi persisten tetap ada.
6. **Uji Sensor Kedekatan (Proximity)**:
   * Menempelkan HP ke telinga; layar mati seketika dan tidak merespons sentuhan tidak sengaja.
