package io.github.oustery.opencodephone.core.linuxenv

import io.github.oustery.opencodephone.core.model.OcJson
import io.github.oustery.opencodephone.core.model.Pins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads a Debian rootfs layer from the Docker registry, pinned by digest.
 *
 * The manifest digest is pinned in [Pins]; every blob in an OCI registry is
 * content-addressed, so the digest is the checksum: the downloaded layer is
 * verified against the layer digest from the manifest while streaming.
 */
class DockerRegistryClient(private val callFactory: Call.Factory) {

    private val json = OcJson.lenient

    private suspend fun pullToken(): String = withContext(Dispatchers.IO) {
        val url = "https://auth.docker.io/token?service=registry.docker.io" +
            "&scope=repository:${Pins.DOCKER_REPO}:pull"
        callFactory.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Docker auth failed: HTTP ${resp.code}" }
            val body = resp.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body).jsonObject
            obj["token"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                ?: error("Docker auth response missing token")
        }
    }

    private fun registryRequest(url: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

    /** Fetch the (pinned) image manifest for one architecture. */
    suspend fun fetchManifest(digest: String): JsonObject = withContext(Dispatchers.IO) {
        val token = pullToken()
        val url = "https://registry-1.docker.io/v2/${Pins.DOCKER_REPO}/manifests/$digest"
        callFactory.newCall(
            registryRequest(url, token)
                .newBuilder()
                .header(
                    "Accept",
                    "application/vnd.oci.image.manifest.v1+json, " +
                        "application/vnd.docker.distribution.manifest.v2+json",
                )
                .build(),
        ).execute().use { resp ->
            check(resp.isSuccessful) { "Manifest fetch failed: HTTP ${resp.code}" }
            val bytes = checkNotNull(resp.body) { "Empty manifest response" }.bytes()
            parseVerifiedManifest(bytes, digest)
        }
    }

    data class Layer(val digest: String, val size: Long)

    fun singleLayer(manifest: JsonObject): Layer {
        val layers = manifest["layers"]?.jsonArray ?: error("Manifest has no layers")
        check(layers.isNotEmpty()) { "Manifest has an empty layer list" }
        val obj = layers.first().jsonObject
        val digest = obj["digest"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            ?: error("Layer without digest")
        val size = obj["size"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() }
            ?: -1L
        return Layer(digest, size)
    }

    /**
     * Download the single layer blob to [dest], verifying its SHA-256 against
     * the content-addressed digest from the manifest. Returns the computed hex
     * digest.
     */
    suspend fun downloadLayer(
        layer: Layer,
        dest: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val token = pullToken()
        val url = "https://registry-1.docker.io/v2/${Pins.DOCKER_REPO}/blobs/${layer.digest}"
        dest.parentFile?.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        callFactory.newCall(registryRequest(url, token)).execute().use { resp ->
            check(resp.isSuccessful) { "Blob download failed: HTTP ${resp.code}" }
            val source = resp.body?.byteStream() ?: error("Empty blob response")
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    bytesRead += read
                    onProgress(bytesRead, layer.size)
                }
            }
        }
        val computed = md.digest().joinToString("") { "%02x".format(it) }
        val expected = layer.digest.removePrefix("sha256:")
        check(computed == expected) {
            "Rootfs digest mismatch: expected $expected, got $computed"
        }
        computed
    }

    companion object {
        /** Hash the original bytes before parsing: JSON reformatting changes an OCI digest. */
        internal fun parseVerifiedManifest(bytes: ByteArray, digest: String): JsonObject {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            check(digest == "sha256:$actual") { "Manifest digest mismatch" }
            return OcJson.lenient.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }

        fun longTimeoutClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
