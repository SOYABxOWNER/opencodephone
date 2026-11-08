package io.github.oustery.opencodephone.core.linuxenv

import io.github.oustery.opencodephone.core.model.OcJson
import io.github.oustery.opencodephone.core.model.OcJson.optObject
import io.github.oustery.opencodephone.core.model.OcJson.optString
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
 * `@opencode/cli-<target>@<version>` and integrity-checked against the
 * registry SRI (sha512).
 */
class OpencodeInstaller(private val callFactory: Call.Factory) {

    private val json = OcJson.lenient

    data class PackageInfo(
        val tarballUrl: String,
        /** Registry integrity field, e.g. `sha512-<base64>`. Null when absent. */
        val integrity: String?,
    )

    /**
     * Resolve the package metadata, trying the primary scope first and
     * falling back to the legacy `@opencode-ai` scope.
     */
    suspend fun resolvePackage(target: String, version: String): PackageInfo {
        val scopes = listOf(Pins.OPENCODE_NPM_SCOPE, "@opencode-ai")
        var lastError: Exception? = null
        for (scope in scopes) {
            val url = "https://registry.npmjs.org/$scope%2fcli-$target/$version"
            try {
                val body = get(url)
                val obj = json.parseToJsonElement(body) as? JsonObject
                    ?: error("unexpected npm metadata shape")
                val dist = obj.optObject("dist") ?: error("npm metadata missing dist")
                val tarball = dist.optString("tarball")
                    ?: error("npm metadata missing dist.tarball")
                return PackageInfo(tarball, dist.optString("integrity"))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            "OpenCode package not found for target=$target version=$version: ${lastError?.message}",
        )
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        callFactory.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
        callFactory.newCall(Request.Builder().url(info.tarballUrl).build()).execute().use { resp ->
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
                    if (outBin.exists()) outBin.delete()
                    FileOutputStream(outBin).use { tar.copyTo(it) }
                    outBin.setExecutable(true, false)
                    found = outBin
                    break
                }
            }
            checkNotNull(found) { "opencode binary not found inside package tarball" }
        }
    }
}
