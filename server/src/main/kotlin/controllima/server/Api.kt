package controllima.server

import controllima.providers.lima.LimaProvider
import controllima.providers.lima.ProviderException
import controllima.shared.ApiError
import controllima.shared.ApiRoutes
import controllima.shared.Health
import controllima.shared.Vm
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Framework-free request handling, so it can be tested and moved off Ktor if needed. */
class Api(private val config: ServerConfig, private val lima: LimaProvider) {
    data class Response(val status: Int, val body: String, val contentType: String = "application/json")

    private val json = Json { encodeDefaults = true }

    /** Token and Origin checks for every /api and /ws request. Null means allowed. */
    fun authorize(path: String, token: String?, origin: String?): Response? {
        if (!path.startsWith("/api") && !path.startsWith("/ws")) return null
        if (origin != null && origin !in config.allowedOrigins) return error(403, "bad_origin", "Origin $origin is not allowed")
        if (token == null || !constantTimeEquals(token, config.token)) return error(401, "unauthorized", "Missing or invalid token")
        return null
    }

    fun health() = Response(200, json.encodeToString(Health.serializer(), Health("ok", config.version)))

    fun vms(): Response = try {
        Response(200, json.encodeToString(ListSerializer(Vm.serializer()), lima.list()))
    } catch (e: ProviderException) {
        error(502, "lima_unavailable", e.message ?: "limactl failed")
    }

    private fun error(status: Int, code: String, message: String) =
        Response(status, json.encodeToString(ApiError.serializer(), ApiError(code, message)))

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        val routes = listOf(ApiRoutes.HEALTH, ApiRoutes.VMS)
    }
}
