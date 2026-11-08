package io.github.oustery.opencodephone.core.linuxenv

import java.net.URI
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Credentials live only in the child environment, never URL, argv, config or helper files. */
object GitAuth {
    fun cleanUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.host != null && uri.rawQuery == null && uri.rawFragment == null) {
            "Git operations require an HTTPS repository URL without query or fragment"
        }
        return URI("https", null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }

    fun command(vararg args: String): List<String> = listOf(
        "git", "-c", "credential.helper=", "-c", "credential.helper=$helper",
        "-c", "http.sslVerify=true", "-c", "http.followRedirects=false", "-c", "core.hooksPath=/dev/null",
    ) + args

    fun environment(url: String, token: String): Map<String, String> {
        val uri = URI(cleanUrl(url))
        return buildMap {
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_ASKPASS", "/bin/false")
            if (uri.host.equals("github.com", ignoreCase = true) && uri.port in listOf(-1, 443) && token.isNotBlank()) {
                put("OPENCODEPHONE_GIT_TOKEN", token)
            }
        }
    }

    suspend fun migrateRemotes(run: suspend (List<String>) -> ExecResult) {
        val result = run(listOf("git", "config", "--local", "--get-regexp", "^remote\\..*\\.(url|pushurl)$"))
        if (result.exitCode == 1) return
        check(result.exitCode == 0) { "Cannot read repository remotes" }
        val entries = result.output.lineSequence().filter { it.isNotBlank() }.map { line ->
            val key = line.substringBefore(' ')
            val value = line.substringAfter(' ')
            key to value
        }.groupBy({ it.first }, { it.second })
        for ((key, values) in entries) {
            val cleaned = values.map { value ->
                val uri = runCatching { URI(value) }.getOrNull()
                if (uri?.rawUserInfo != null) {
                    require(uri.host != null && uri.scheme in listOf("http", "https")) { "Unsupported credential URL" }
                    URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
                } else value
            }
            if (cleaned != values) {
                // Each command has the runtime's process timeout. Do not let view/project
                // cancellation interrupt the remove/repopulate transaction and lose URLs.
                withContext(NonCancellable) {
                    check(run(listOf("git", "config", "--local", "--unset-all", key)).exitCode == 0)
                    for (value in cleaned) check(run(listOf("git", "config", "--local", "--add", key, value)).exitCode == 0)
                }
            }
        }
    }

    private val helper = """!f() {
        [ "${'$'}1" = get ] || exit 0
        protocol= host=
        while IFS='=' read -r key value; do
            case "${'$'}key" in protocol) protocol=${'$'}value;; host) host=${'$'}value;; esac
        done
        [ "${'$'}protocol" = https ] || exit 0
        case "${'$'}host" in github.com|github.com:443) ;; *) exit 0;; esac
        [ -n "${'$'}OPENCODEPHONE_GIT_TOKEN" ] || exit 0
        printf 'username=x-access-token\npassword=%s\n\n' "${'$'}OPENCODEPHONE_GIT_TOKEN"
    }; f""".trimIndent()
}
