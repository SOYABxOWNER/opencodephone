package io.github.oustery.opencodephone.core.linuxenv

import io.github.oustery.opencodephone.core.model.OcJson
import io.github.oustery.opencodephone.core.model.OcJson.optObject
import io.github.oustery.opencodephone.core.model.OcJson.optString
import io.github.oustery.opencodephone.core.opencode.consumeCancellable
import io.github.oustery.opencodephone.core.model.Pins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Installs the OpenCode CLI into the Debian environment without Node.js:
 * the binary is fetched from the npm registry package
 * `opencode-<target>@<version>` and integrity-checked against the
 * registry SRI (sha512).
 */
class OpencodeInstaller(
    private val callFactory: Call.Factory,
    private val registryBaseUrl: String = "https://registry.npmjs.org",
) {

    private val json = OcJson.lenient

    data class PackageInfo(
        val tarballUrl: String,
        /** Registry integrity field, e.g. `sha512-<base64>`. Null when absent. */
        val integrity: String?,
    )

    /** The client implements OpenCode V1, not the unrelated V2 API. */
    suspend fun resolvePackage(target: String, version: String): PackageInfo {
        require(version.matches(Regex("1\\.\\d+\\.\\d+([-.][A-Za-z0-9.-]+)?"))) {
            "This app supports OpenCode V1. Choose a 1.x CLI version (recommended: ${Pins.OPENCODE_VERSION})."
        }
        val url = "$registryBaseUrl/${Pins.OPENCODE_NPM_PACKAGE_PREFIX}$target/$version"
        val obj = json.parseToJsonElement(get(url)) as? JsonObject
            ?: error("Unexpected npm metadata shape")
        val dist = obj.optObject("dist") ?: error("npm metadata missing dist")
        return PackageInfo(
            dist.optString("tarball") ?: error("npm metadata missing dist.tarball"),
            dist.optString("integrity") ?: error("npm metadata missing integrity"),
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        callFactory.newCall(Request.Builder().url(url).build()).consumeCancellable { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            resp.body?.string().orEmpty()
        }
    }

    /** Download the package tarball with progress and SRI verification. */
    suspend fun downloadTarball(
        info: PackageInfo,
        dest: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-512")
        var bytesRead = 0L
        var total = -1L
        callFactory.newCall(Request.Builder().url(info.tarballUrl).build()).consumeCancellable { resp ->
            check(resp.isSuccessful) { "Tarball download failed: HTTP ${resp.code}" }
            val body = resp.body ?: error("Empty tarball response")
            total = body.contentLength()
            val source = body.byteStream()
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    bytesRead += read
                    onProgress(bytesRead, total)
                }
            }
        }
        info.integrity?.let { sri ->
            val expected = sri.removePrefix("sha512-")
            val computed = Base64.getEncoder().encodeToString(md.digest())
            check(computed == expected) { "OpenCode tarball integrity mismatch" }
        }
        Unit
    }

    /**
     * Extract the `opencode` binary from the package tarball into [outBin].
     * The package layout is searched rather than hardcoded, so minor package
     * structure changes do not break installation.
     */
    fun extractBinary(tarball: File, outBin: File) {
        check(tarball.exists()) { "Tarball not found: $tarball" }
        TarArchiveInputStream(
            GZIPInputStream(BufferedInputStream(FileInputStream(tarball)), 128 * 1024),
        ).use { tar ->
            var found: File? = null
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name == "opencode" && (entry.mode and 0b001_000_000) != 0) {
                    outBin.parentFile?.mkdirs()
                    val staging = File(outBin.parentFile, "${outBin.name}.new")
                    try {
                        FileOutputStream(staging).use { tar.copyTo(it) }
                        check(staging.setExecutable(true, false)) { "Cannot mark CLI executable" }
                        java.nio.file.Files.move(
                            staging.toPath(), outBin.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        staging.delete()
                    }
                    found = outBin
                    break
                }
            }
            checkNotNull(found) { "opencode binary not found inside package tarball" }
        }
    }
}
