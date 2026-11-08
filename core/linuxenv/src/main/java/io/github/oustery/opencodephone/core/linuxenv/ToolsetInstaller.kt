package io.github.oustery.opencodephone.core.linuxenv

import io.github.oustery.opencodephone.core.opencode.consumeCancellable
import io.github.oustery.opencodephone.core.model.Pins
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Installs the pre-resolved toolset (git, curl, ripgrep + dependency closure)
 * into the rootfs by downloading a digest-pinned tarball and extracting it
 * app-side, then merging the dpkg status entries so apt/dpkg inside the
 * environment recognize the packages as installed.
 *
 * Running apt-get inside proot was replaced by this approach because Android
 * 12+ kills high-CPU child processes ("phantom process killer"), which made
 * the bootstrap fail with exit 255 and no output (see ADR-008).
 */
class ToolsetInstaller(private val callFactory: Call.Factory) {

    /**
     * Download the toolset for [arch] into [layout.downloadDir], verify its
     * sha256 against [Pins.TOOLSET_SHA256] and extract it into the rootfs.
     */
    suspend fun install(
        layout: EnvLayout,
        arch: String,
        onProgress: (read: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val digest = Pins.TOOLSET_SHA256[arch]
            ?: error("No toolset pinned for architecture $arch")
        val url = Pins.TOOLSET_URL_PREFIX + arch + Pins.TOOLSET_URL_SUFFIX
        val archive = File(layout.downloadDir, "toolset-$arch.tar.gz")

        val sha = MessageDigest.getInstance("SHA-256")
        callFactory.newCall(Request.Builder().url(url).build()).consumeCancellable { resp ->
            check(resp.isSuccessful) { "Toolset download failed: HTTP ${resp.code} for $url" }
            val body = resp.body ?: error("Toolset download: empty body")
            val total = body.contentLength()
            FileOutputStream(archive).use { out ->
                val buf = ByteArray(256 * 1024)
                var read = 0L
                while (true) {
                    val n = body.byteStream().read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        check(got == digest) {
            "Toolset checksum mismatch: expected $digest, got $got ($url)"
        }

        RootfsExtractor.extract(archive, layout.rootfsDir, replace = false, checkCancellation = currentCoroutineContext().let { ctx -> { ctx.ensureActive() } })
        archive.delete()

        mergeDpkgStatus(layout.rootfsDir)
    }

    /**
     * Merge `var/lib/dpkg/status.d/<pkg>` stanzas extracted from the toolset
     * into the rootfs dpkg database, replacing same-named packages (the
     * toolset usually carries newer library versions than the pinned rootfs).
     */
    internal fun mergeDpkgStatus(rootfsDir: File) {
        val dpkgDir = File(rootfsDir, "var/lib/dpkg")
        val fragmentsDir = File(dpkgDir, "status.d")
        if (!fragmentsDir.isDirectory) return
        val statusFile = File(dpkgDir, "status")

        val existing = LinkedHashMap<String, String>()
        if (statusFile.exists()) {
            statusFile.readText().split("\n\n").forEach { stanza ->
                val name = stanza.lineSequence()
                    .firstOrNull { it.startsWith("Package: ") }
                    ?.removePrefix("Package: ")?.trim()
                if (!name.isNullOrBlank() && stanza.isNotBlank()) existing[name] = stanza
            }
        }
        fragmentsDir.listFiles()?.sortedBy { it.name }?.forEach { frag ->
            val stanza = frag.readText().trim()
            val name = stanza.lineSequence()
                .firstOrNull { it.startsWith("Package: ") }
                ?.removePrefix("Package: ")?.trim()
            if (!name.isNullOrBlank()) existing[name] = stanza
        }
        statusFile.writeText(existing.values.joinToString("\n\n") + "\n")
        // Fragments served their purpose; keep the db canonical.
        fragmentsDir.deleteRecursively()
    }
}
