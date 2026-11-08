package io.github.oustery.opencodephone.ui.update

import kotlinx.serialization.json.*

internal data class ReleaseApk(val version: String, val url: String, val size: Long, val digest: String?)

/** Only stable releases from this repository, with an exact ABI asset name. */
internal object ReleasePolicy {
    const val MAX_APK = 150L * 1024 * 1024
    fun newer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Long>? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
            return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        }
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    fun select(json: String, current: String, abis: List<String>): ReleaseApk? {
        val release = Json.parseToJsonElement(json).jsonObject
        check(release["draft"]?.jsonPrimitive?.booleanOrNull == false &&
            release["prerelease"]?.jsonPrimitive?.booleanOrNull == false)
        val tag = release.getValue("tag_name").jsonPrimitive.content
        if (!newer(tag, current)) return null
        val assets = release.getValue("assets").jsonArray.map { it.jsonObject }
        for (abi in abis.filter { it in listOf("arm64-v8a", "armeabi-v7a", "x86_64") }) {
            val name = "OpenCodePhone-$tag-$abi.apk"
            val asset = assets.singleOrNull { it["name"]?.jsonPrimitive?.content == name } ?: continue
            val url = asset.getValue("browser_download_url").jsonPrimitive.content
            check(url == "https://github.com/oustery/opencodephone/releases/download/$tag/$name")
            val size = asset.getValue("size").jsonPrimitive.long
            check(size in 1..MAX_APK)
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
            check(digest == null || Regex("sha256:[a-fA-F0-9]{64}").matches(digest))
            return ReleaseApk(tag.removePrefix("v"), url, size, digest?.substringAfter(':'))
        }
        error("No compatible APK")
    }
}
