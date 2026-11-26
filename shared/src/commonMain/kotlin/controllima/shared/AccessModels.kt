package controllima.shared

import kotlinx.serialization.Serializable

/** Roles from docs/plan/auth.md; each one includes the rights of the ones before it. */
@Serializable
enum class Role {
    VIEWER, OPERATOR, ADMIN;

    fun allows(required: Role): Boolean = ordinal >= required.ordinal
}

/** `GET /api/session`. */
@Serializable
data class Session(
    val actor: String,
    val role: Role,
    val csrfToken: String,
    val protectedNames: List<String>,
    val version: String,
    val multiUser: Boolean = false,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Body of every destructive request: the user typed the target's name. */
@Serializable
data class ConfirmRequest(val confirmName: String)

@Serializable
data class RenameRequest(val newName: String, val confirmName: String? = null)

@Serializable
data class CloneRequest(val newName: String)

@Serializable
data class TokenInfo(
    val id: String,
    val name: String,
    val role: Role,
    val createdMillis: Long,
    val expiresMillis: Long? = null,
    val lastUsedMillis: Long? = null,
)

@Serializable
data class CreateTokenRequest(val name: String, val role: Role, val expiresInDays: Int? = null)

/** The secret is in this response only; it is stored hashed. */
@Serializable
data class CreatedToken(val info: TokenInfo, val token: String)

@Serializable
data class UserInfo(val username: String, val role: Role, val createdMillis: Long)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val role: Role)

@Serializable
data class AuditEntry(
    val timeMillis: Long,
    val actor: String,
    val role: Role? = null,
    val action: String,
    val target: String? = null,
    val params: Map<String, String> = emptyMap(),
    val result: String,
    val durationMs: Long = 0,
)

@Serializable
data class RevealRequest(val key: String)

@Serializable
data class RevealedValue(val key: String, val value: String)
