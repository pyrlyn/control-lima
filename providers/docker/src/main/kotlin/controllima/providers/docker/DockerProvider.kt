package controllima.providers.docker

import controllima.shared.Container
import controllima.shared.ContainerPort
import controllima.shared.Image
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class DockerException(message: String) : RuntimeException(message)

/** Containers and images of one Docker daemon (Colima profile). */
interface DockerProvider {
    fun containers(): List<Container>
    fun images(): List<Image>
}

/** Raw HTTP transport to the Engine API, so the provider can be tested with fixtures. */
interface DockerTransport {
    /** GET [path] and return the body; throws [DockerException] on non-2xx or timeout. */
    fun get(path: String): String
}

class DockerEngineProvider(private val transport: DockerTransport) : DockerProvider {
    override fun containers(): List<Container> = DockerJson.parseContainers(transport.get("/v1.43/containers/json?all=true"))
    override fun images(): List<Image> = DockerJson.parseImages(transport.get("/v1.43/images/json"))
}

/** Parsers for Engine API responses. Unknown fields are ignored. */
object DockerJson {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class PortDto(
        @SerialName("PrivatePort") val privatePort: Int,
        @SerialName("PublicPort") val publicPort: Int? = null,
        @SerialName("IP") val ip: String? = null,
        @SerialName("Type") val type: String = "tcp",
    )

    @Serializable
    private data class EndpointDto(@SerialName("IPAddress") val ipAddress: String? = null)

    @Serializable
    private data class NetworksDto(@SerialName("Networks") val networks: Map<String, EndpointDto> = emptyMap())

    @Serializable
    private data class ContainerDto(
        @SerialName("Id") val id: String,
        @SerialName("Names") val names: List<String> = emptyList(),
        @SerialName("Image") val image: String = "",
        @SerialName("State") val state: String = "",
        @SerialName("Status") val status: String = "",
        @SerialName("Created") val created: Long? = null,
        @SerialName("Ports") val ports: List<PortDto> = emptyList(),
        @SerialName("NetworkSettings") val networkSettings: NetworksDto? = null,
    )

    @Serializable
    private data class ImageDto(
        @SerialName("Id") val id: String,
        @SerialName("RepoTags") val repoTags: List<String>? = null,
        @SerialName("Size") val size: Long = 0,
        @SerialName("Created") val created: Long? = null,
        @SerialName("Containers") val containers: Int? = null,
    )

    fun parseContainers(body: String): List<Container> =
        json.decodeFromString(ListSerializer(ContainerDto.serializer()), body).map { c ->
            Container(
                id = c.id,
                name = c.names.firstOrNull()?.removePrefix("/") ?: c.id.take(12),
                image = c.image,
                state = c.state,
                status = c.status,
                ports = c.ports.map { ContainerPort(it.privatePort, it.publicPort, it.ip, it.type) },
                ipAddresses = c.networkSettings?.networks?.values?.mapNotNull { it.ipAddress?.takeIf(String::isNotBlank) }.orEmpty(),
                createdEpochSeconds = c.created,
            )
        }

    fun parseImages(body: String): List<Image> =
        json.decodeFromString(ListSerializer(ImageDto.serializer()), body).map { i ->
            Image(
                id = i.id,
                tags = i.repoTags.orEmpty().filter { it != "<none>:<none>" },
                sizeBytes = i.size,
                createdEpochSeconds = i.created,
                containers = i.containers?.takeIf { it >= 0 },
            )
        }
}
