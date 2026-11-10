package de.ricci.nexus.ota

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** Validation shared by Android clients before they open the system installer. */
object AndroidOta {
    fun verifySha256(apk: File, expectedHex: String) {
        require(expectedHex.matches(Regex("[0-9a-f]{64}"))) { "Ungültige APK-Prüfsumme." }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes < 0) break
                digest.update(buffer, 0, bytes)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expectedHex) { "APK-Prüfsumme stimmt nicht überein." }
    }

    fun verifyUpdate(context: Context, apk: File, versionCode: Long? = null) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: error("Ungültige Update-APK.")
        require(candidate.packageName == context.packageName) { "Update gehört zu einer anderen App." }
        require(candidate.longVersionCode > installed.longVersionCode) { "Diese APK ist nicht neuer." }
        require(versionCode == null || candidate.longVersionCode == versionCode) {
            "APK-Version stimmt nicht mit den Update-Metadaten überein."
        }
        val expected = installed.signingInfo?.apkContentsSigners
        val actual = candidate.signingInfo?.apkContentsSigners
        require(!expected.isNullOrEmpty() && !actual.isNullOrEmpty() && expected.toSet() == actual.toSet()) {
            "Update hat eine andere Signatur."
        }
    }
}
