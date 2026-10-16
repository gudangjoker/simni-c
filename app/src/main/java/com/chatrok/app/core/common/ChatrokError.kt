package com.chatrok.app.core.common

import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException

open class ChatrokDomainException(
    val errorCode: String,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: errorCode, cause)

class UsernameAlreadyTakenException(
    val username: String
) : ChatrokDomainException("chatrok/username-taken", "chatrok/username-taken: Username '$username' sudah digunakan.")

class RegistrationRollbackFailedException(
    val originalError: Throwable,
    val rollbackError: Throwable
) : ChatrokDomainException(
    "chatrok/registration-rollback-failed",
    "Gagal melakukan pembatalan akun setelah kegagalan pendaftaran: ${rollbackError.localizedMessage ?: "error tidak diketahui"}",
    originalError
)

class UsernamePrecheckFailedException(
    val originalError: Throwable
) : ChatrokDomainException(
    "chatrok/username-precheck-failed",
    "Gagal memverifikasi ketersediaan username: ${originalError.localizedMessage ?: "gangguan koneksi/server"}",
    originalError
)

class UserProfileNotFoundException(
    val uid: String
) : ChatrokDomainException(
    "chatrok/user-profile-not-found",
    "Profil pengguna dengan UID '$uid' tidak ditemukan pada Firestore."
)

class UserProfileLoadException(
    val uid: String,
    cause: Throwable
) : ChatrokDomainException(
    "chatrok/user-profile-load-failed",
    "Gagal memuat profil pengguna '$uid': ${cause.localizedMessage ?: "gangguan koneksi/server"}",
    cause
)

class ProfileUpdateFailedException(
    val uid: String,
    cause: Throwable
) : ChatrokDomainException(
    "chatrok/profile-update-failed",
    "Gagal memperbarui profil pengguna '$uid': ${cause.localizedMessage ?: "gangguan koneksi/server"}",
    cause
)

class FeatureUnavailableException(
    val feature: String = "block_user",
    message: String = "Fitur pemblokiran pengguna dijadwalkan pada milestone berikutnya."
) : UnsupportedOperationException(message) {
    val errorCode: String = "chatrok/feature-unavailable"
}

object ChatrokError {

    fun formatAuthError(error: Throwable?): String {
        if (error == null) return "Terjadi kesalahan pada proses autentikasi."

        val code = when (error) {
            is ChatrokDomainException -> "${error.errorCode} ${error.message ?: ""}"
            is FirebaseAuthException -> error.errorCode
            is FirebaseException -> error.message ?: ""
            else -> error.message ?: ""
        }

        return when {
            code.contains("email-already-in-use", ignoreCase = true) ||
                    code.contains("ERROR_EMAIL_ALREADY_IN_USE", ignoreCase = true) ->
                "Alamat email ini sudah terdaftar. Silakan masuk atau gunakan email lain."

            code.contains("invalid-email", ignoreCase = true) ||
                    code.contains("ERROR_INVALID_EMAIL", ignoreCase = true) ->
                "Format alamat email tidak valid."

            code.contains("weak-password", ignoreCase = true) ||
                    code.contains("ERROR_WEAK_PASSWORD", ignoreCase = true) ->
                "Kata sandi terlalu lemah. Gunakan minimal 6 karakter."

            code.contains("wrong-password", ignoreCase = true) ||
                    code.contains("ERROR_WRONG_PASSWORD", ignoreCase = true) ||
                    code.contains("invalid-credential", ignoreCase = true) ||
                    code.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ->
                "Kata sandi atau email tidak cocok. Periksa kembali data akun Anda."

            code.contains("user-not-found", ignoreCase = true) ||
                    code.contains("ERROR_USER_NOT_FOUND", ignoreCase = true) ->
                "Akun dengan email ini tidak ditemukan."

            code.contains("user-disabled", ignoreCase = true) ||
                    code.contains("ERROR_USER_DISABLED", ignoreCase = true) ->
                "Akun ini telah dinonaktifkan oleh administrator."

            code.contains("network-request-failed", ignoreCase = true) ->
                "Koneksi jaringan terputus. Periksa sambungan internet Anda."

            code.contains("chatrok/username-taken", ignoreCase = true) ->
                "Username ini sudah digunakan oleh pengguna lain. Silakan pilih username lain."

            code.contains("chatrok/registration-rollback-failed", ignoreCase = true) ->
                "Pendaftaran gagal dan pembatalan akun terkendala koneksi. Silakan hubungi administrator."

            code.contains("chatrok/username-precheck-failed", ignoreCase = true) ->
                "Gagal memeriksa ketersediaan username karena kendala jaringan atau server. Silakan coba lagi."

            code.contains("chatrok/username-invalid", ignoreCase = true) ->
                "Username harus 3-30 karakter (hanya huruf, angka, titik, atau garis bawah)."

            code.contains("chatrok/display-name-empty", ignoreCase = true) ->
                "Nama lengkap / tampilan tidak boleh kosong."

            code.contains("chatrok/password-mismatch", ignoreCase = true) ->
                "Konfirmasi kata sandi tidak cocok."

            code.contains("chat/e2ee-restore-failed", ignoreCase = true) ||
                    code.contains("chat/account-backup-failed", ignoreCase = true) ->
                "Gagal memulihkan kunci enkripsi. Kata sandi atau data backup tidak valid."

            code.contains("chat/fingerprint-mismatch", ignoreCase = true) ->
                "Integritas identitas enkripsi E2EE tidak cocok dengan data server. Akses obrolan ditolak."

            code.contains("chat/key-backup-missing", ignoreCase = true) ->
                "Cadangan kunci enkripsi server tidak ditemukan untuk akun ini."

            code.contains("chat/e2ee-setup-failed", ignoreCase = true) ->
                "Gagal menyiapkan enkripsi akun baru. Silakan coba mendaftar kembali."

            code.contains("chatrok/user-profile-not-found", ignoreCase = true) ->
                "Profil akun tidak ditemukan. Silakan hubungi administrator atau daftar ulang."

            code.contains("chatrok/user-profile-load-failed", ignoreCase = true) ->
                "Gagal memuat profil pengguna. Periksa koneksi internet Anda."

            code.contains("chatrok/profile-update-failed", ignoreCase = true) ->
                "Gagal memperbarui profil akun. Silakan periksa koneksi atau coba beberapa saat lagi."

            code.contains("chatrok/feature-unavailable", ignoreCase = true) ||
                    error is FeatureUnavailableException ||
                    error is UnsupportedOperationException ->
                error.message ?: "Fitur ini dijadwalkan pada pembaruan berikutnya."

            else -> error.localizedMessage ?: "Terjadi kesalahan pada proses autentikasi."
        }
    }
}
