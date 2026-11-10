package io.github.oustery.opencodephone.core.opencode

import io.github.oustery.opencodephone.core.model.OcJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * One pending device-authorization attempt against the OpenCode console
 * (`opencode account login` equivalent). [userCode] is the short code the
 * user confirms at [verificationUrl].
 */
data class ConsoleLogin(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    /** Epoch ms after which the device code can no longer be approved. */
    val expiresAtMs: Long,
    /** Server-advertised polling interval in ms (`slow_down` grows it). */
    val intervalMs: Long,
)

/** Outcome of one device-token poll or refresh attempt. */
sealed interface ConsolePollResult {
    /** The user has not confirmed the code yet; keep polling. */
    data object Pending : ConsolePollResult
    /** The server asked to slow down; increase the interval and continue. */
    data object SlowDown : ConsolePollResult
    /** The device code expired without being approved. */
    data object Expired : ConsolePollResult
    /** The user explicitly denied the authorization. */
    data object Denied : ConsolePollResult
    data class Success(
        val accessToken: String,
        val refreshToken: String,
        val expiresInMs: Long,
    ) : ConsolePollResult
    data class Failure(val message: String) : ConsolePollResult
}

data class ConsoleUser(val id: String, val email: String)

data class ConsoleOrg(val id: String, val name: String)

/**
 * Client for the OpenCode console (opencode.ai) device-authorization flow.
 *
 * Endpoints verified against `sst/opencode` tag v1.19.5
 * (`packages/opencode/src/account/account.ts`, client id `opencode-cli`):
 * `POST /auth/device/code`, `POST /auth/device/token` (device and refresh
 * grants), `GET /api/user`, `GET /api/orgs`. The access token works as the
 * API key of the `opencode` (Zen) provider, so the app pushes it to the
 * opencode server through the regular provider-key sync.
 */
class OpenCodeConsoleClient(
    private val baseUrl: String = DEFAULT_URL,
    private val client: OkHttpClient = OpenCodeClient.defaultHttpClient(),
) {

    private val json = OcJson.lenient

    companion object {
        /** Default console URL used by the upstream CLI (`account.ts`). */
        const val DEFAULT_URL = "https://opencode.ai/console"

        /** Public device-flow client id registered for opencode clients. */
        const val CLIENT_ID = "opencode-cli"

        private const val GRANT_DEVICE = "urn:ietf:params:oauth:grant-type:device_code"
        private const val GRANT_REFRESH = "refresh_token"
    }

    private suspend fun post(path: String, body: String, token: String? = null): Pair<Int, String> {
        val builder = Request.Builder()
            .url(baseUrl.removeSuffix("/") + path)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.newCall(builder.build()).consumeCancellable { response ->
            response.bodyText()
        }
    }

    private suspend fun get(path: String, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(baseUrl.removeSuffix("/") + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return client.newCall(request).consumeCancellable { response ->
            response.bodyText()
        }
    }

    private fun Response.bodyText(): Pair<Int, String> = code to (body?.string().orEmpty())

    private fun JsonObject.str(key: String): String? = OcJson.run { optString(key) }

    private fun textToObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /**
     * Start the device flow: returns the user code to confirm and the
     * verification URL to open in a browser.
     */
    suspend fun startLogin(): ConsoleLogin {
        val body = buildJsonObject { put("client_id", CLIENT_ID) }.encode()
        val (status, text) = post("/auth/device/code", body)
        require(status in 200..299) { "Console device-code request failed: HTTP $status" }
        val payload = textToObject(text)
            ?: throw IllegalStateException("Console device-code response is not JSON")
        val deviceCode = payload.str("device_code")
            ?: throw IllegalStateException("Console response has no device_code")
        val verification = payload.str("verification_uri_complete")
            ?: payload.str("verification_uri")
            ?: throw IllegalStateException("Console response has no verification URL")
        val expiresInSec = payload.str("expires_in")?.toLongOrNull() ?: 600L
        val intervalSec = payload.str("interval")?.toLongOrNull() ?: 5L
        return ConsoleLogin(
            deviceCode = deviceCode,
            userCode = payload.str("user_code").orEmpty(),
            verificationUrl = verification,
            expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000,
            intervalMs = intervalSec * 1000,
        )
    }

    /** Poll the token endpoint once with the device grant. */
    suspend fun poll(login: ConsoleLogin): ConsolePollResult {
        val body = buildJsonObject {
            put("grant_type", GRANT_DEVICE)
            put("device_code", login.deviceCode)
            put("client_id", CLIENT_ID)
        }.encode()
        val (status, text) = post("/auth/device/token", body)
        val payload = textToObject(text) ?: JsonObject(emptyMap())
        if (status in 200..299) {
            val access = payload.str("access_token")
            val refresh = payload.str("refresh_token")
            return if (access != null && refresh != null) {
                ConsolePollResult.Success(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresInMs = (payload.str("expires_in")?.toLongOrNull() ?: 3600L) * 1000,
                )
            } else {
                ConsolePollResult.Failure("Console token response is incomplete")
            }
        }
        return when (payload.str("error")) {
            "authorization_pending" -> ConsolePollResult.Pending
            "slow_down" -> ConsolePollResult.SlowDown
            "expired_token" -> ConsolePollResult.Expired
            "access_denied" -> ConsolePollResult.Denied
            null -> ConsolePollResult.Failure("Console token request failed: HTTP $status")
            else -> ConsolePollResult.Failure(payload.str("error_description") ?: "Console rejected the request")
        }
    }

    /** Exchange the refresh token for a fresh access token pair, or null on failure. */
    suspend fun refresh(refreshToken: String): ConsolePollResult.Success? {
        val body = buildJsonObject {
            put("grant_type", GRANT_REFRESH)
            put("refresh_token", refreshToken)
            put("client_id", CLIENT_ID)
        }.encode()
        val (status, text) = post("/auth/device/token", body)
        if (status !in 200..299) return null
        val payload = textToObject(text) ?: return null
        val access = payload.str("access_token") ?: return null
        val refresh = payload.str("refresh_token") ?: return null
        return ConsolePollResult.Success(
            accessToken = access,
            refreshToken = refresh,
            expiresInMs = (payload.str("expires_in")?.toLongOrNull() ?: 3600L) * 1000,
        )
    }

    /** The signed-in account identity (`GET /api/user`). */
    suspend fun user(accessToken: String): ConsoleUser {
        val (status, text) = get("/api/user", accessToken)
        require(status in 200..299) { "Console user request failed: HTTP $status" }
        val payload = textToObject(text)
            ?: throw IllegalStateException("Console user response is not JSON")
        return ConsoleUser(
            id = payload.str("id").orEmpty(),
            email = payload.str("email").orEmpty(),
        )
    }

    /** Organizations the account belongs to (`GET /api/orgs`). */
    suspend fun orgs(accessToken: String): List<ConsoleOrg> {
        val (status, text) = get("/api/orgs", accessToken)
        if (status !in 200..299) return emptyList()
        val arr = runCatching {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            (el as? JsonObject)?.let { ConsoleOrg(it.str("id").orEmpty(), it.str("name").orEmpty()) }
        }.filter { it.id.isNotBlank() }
    }

    private fun JsonObject.encode(): String = json.encodeToString(JsonObject.serializer(), this)
}
