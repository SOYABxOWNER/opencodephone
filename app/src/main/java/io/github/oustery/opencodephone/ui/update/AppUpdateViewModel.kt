package io.github.oustery.opencodephone.ui.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.oustery.opencodephone.BuildConfig
import io.github.oustery.opencodephone.core.opencode.consumeCancellable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
internal data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val version: String = "",
    val progress: Int = 0,
    /** Failure reason for [UpdatePhase.ERROR]; shown under the phase text. */
    val error: String? = null,
)

internal class AppUpdateViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableStateFlow(UpdateState())
    private val http = OkHttpClient.Builder().followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()
    private var release: ReleaseApk? = null
    private var job: Job? = null
    private val apk get() = File(getApplication<Application>().cacheDir, "updates/update.apk")

    fun check() {
        if (job?.isActive == true) return
        state.value = UpdateState(UpdatePhase.CHECKING)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = http.newCall(Request.Builder()
                    .url("https://api.github.com/repos/oustery/opencodephone/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()).consumeCancellable { response ->
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    body.byteStream().use { input ->
                        val bytes = input.readBytesLimited(2 * 1024 * 1024)
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                release = ReleasePolicy.select(json, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
                state.value = release?.let { UpdateState(UpdatePhase.AVAILABLE, it.version) }
                    ?: UpdateState(UpdatePhase.CURRENT)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UpdateState(UpdatePhase.ERROR, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    fun download() {
        if (job?.isActive == true) return
        val selected = release ?: return
        state.value = UpdateState(UpdatePhase.DOWNLOADING, selected.version)
        job = viewModelScope.launch(Dispatchers.IO) {
            val part = File(apk.parentFile, "update.part")
            try {
                check(apk.parentFile!!.mkdirs() || apk.parentFile!!.isDirectory)
                apk.delete(); part.delete()
                val context = currentCoroutineContext()
                val hash = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(selected.url).build()).consumeCancellable { response ->
                    check(response.isSuccessful && response.request.url.isHttps)
                    val body = checkNotNull(response.body)
                    check(body.contentLength() == -1L || body.contentLength() == selected.size)
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= selected.size)
                                output.write(buffer, 0, count); hash.update(buffer, 0, count)
                                val percent = (100 * total / selected.size).toInt()
                                if (percent != state.value.progress) state.value = state.value.copy(progress = percent)
                            }
                            check(total == selected.size)
                            output.fd.sync()
                        }
                    }
                }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                check(selected.digest == null || digest.equals(selected.digest, ignoreCase = true))
                verifyPackage(part)
                check(part.renameTo(apk))
                check(apk.setReadOnly())
                state.value = UpdateState(UpdatePhase.READY, selected.version, 100)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UpdateState(UpdatePhase.ERROR, selected.version, error = e.message ?: e.javaClass.simpleName) }
            finally { part.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val candidate = checkNotNull(pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES))
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
        check(candidate.packageName == app.packageName)
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(code > oldCode)
        val expected = installed.signatures?.map { it.toCharsString() }?.toSet()
        check(!expected.isNullOrEmpty() && candidate.signatures?.map { it.toCharsString() }?.toSet() == expected)
    }

    fun install(context: android.content.Context) {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:${context.packageName}")))
                return // User returns and explicitly presses Install again.
            }
            check(state.value.phase == UpdatePhase.READY && apk.isFile)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            state.value = UpdateState(UpdatePhase.ERROR, state.value.version, error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun cancel() {
        job?.cancel()
        state.value = UpdateState()
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
