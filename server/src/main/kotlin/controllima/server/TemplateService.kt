package controllima.server

import controllima.providers.lima.CreateFlags
import controllima.providers.lima.LimaProvider
import controllima.server.http.conflict
import controllima.server.http.notFound
import controllima.server.security.Principal
import controllima.server.security.Redactor
import controllima.server.security.Secrets
import controllima.shared.ApiJson
import controllima.shared.CreateVmPreview
import controllima.shared.CreateVmRequest
import controllima.shared.Names
import controllima.shared.SaveTemplateRequest
import controllima.shared.Settings
import controllima.shared.Targets
import controllima.shared.VmRef
import controllima.shared.VmSource
import controllima.shared.VmTemplate
import java.io.File

/**
 * Built-in and custom VM templates (docs/plan/templates.md). Creating from a template always leaves
 * the VM stopped; starting it is a separate user action.
 */
class TemplateService(
    private val paths: DataPaths,
    private val backends: Backends,
    private val inventory: Inventory,
    private val actions: ActionManager,
    private val settings: () -> Settings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var installed: Pair<Long, List<String>>? = null

    /** Template names the installed Lima ships (`limactl info`), cached for 10 minutes. */
    private fun installedTemplates(): List<String> {
        installed?.takeIf { clock() - it.first < 600_000 }?.let { return it.second }
        val names = runCatching { lima().info().templates.map { it.name } }.getOrDefault(emptyList())
        installed = clock() to names
        return names
    }

    private fun lima(home: String? = null): LimaProvider =
        backends.lima(home) ?: backends.limaProviders().firstOrNull() ?: throw IllegalStateException("Lima is not configured")

    fun list(): List<VmTemplate> {
        val available = installedTemplates()
        val curated = BUILT_IN.filter { t -> t.colima || available.isEmpty() || t.base.removePrefix("template://") in available }
        val extra = available.filter { name -> curated.none { it.base == "template://$name" } }.map { name ->
            VmTemplate("lima.$name", name, "template://$name", "Lima template $name", builtIn = true)
        }
        return curated + extra + custom()
    }

    private fun custom(): List<VmTemplate> =
        paths.templates.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching { ApiJson.decodeFromString(VmTemplate.serializer(), f.readText()) }.getOrNull()?.copy(builtIn = false)
        }

    fun get(id: String): VmTemplate = list().firstOrNull { it.id == id } ?: notFound("No template $id")

    /** Saves a template from YAML or from an existing Lima VM's `lima.yaml`. Secrets are flagged, not stored silently. */
    fun save(request: SaveTemplateRequest): Pair<VmTemplate, List<String>> {
        require(request.name.isNotBlank() && request.name.length <= 80) { "Template name is required (max 80 characters)" }
        require(request.description.length <= 500) { "Description is too long" }
        val fromVm = request.fromVm
        val yaml = when {
            fromVm != null -> {
                val ref = Targets.parseVm(fromVm) ?: throw IllegalArgumentException("Unknown VM $fromVm")
                require(ref.source == VmSource.LIMA) { "Templates can be saved from Lima VMs only" }
                val vm = inventory.find(ref) ?: notFound("No VM ${ref.name}")
                File(vm.dir ?: throw IllegalStateException("VM ${ref.name} has no instance directory"), "lima.yaml").readText()
            }
            else -> request.yaml ?: throw IllegalArgumentException("Give a VM or YAML")
        }
        require(yaml.length <= MAX_YAML) { "Template YAML is larger than 256 KB" }
        val findings = Redactor(settings().redactionPatterns).scan(yaml)
        val id = "custom-" + Secrets.randomHex(4)
        val template = VmTemplate(id, request.name.trim(), "custom", request.description.trim(), builtIn = false, yaml = yaml)
        writePrivate(File(paths.templates, "$id.json"), ApiJson.encodeToString(VmTemplate.serializer(), template))
        return template to findings
    }

    fun delete(id: String) {
        val template = get(id)
        if (template.builtIn) conflict("Built-in templates cannot be deleted")
        File(paths.templates, "$id.json").delete()
    }

    /** What would run: the exact `limactl create` command and, for YAML-based templates, the generated file. */
    fun preview(id: String, request: CreateVmRequest): CreateVmPreview {
        val template = get(id)
        validate(request)
        val warnings = mutableListOf<String>()
        if (template.colima) {
            warnings += "Colima creates and starts a profile in one step; ControlLima never starts VMs on its own, so run this yourself."
            val args = buildList {
                add("colima"); add("start"); add("--profile"); add(request.name)
                (request.cpus ?: template.cpus)?.let { add("--cpu"); add("$it") }
                (request.memoryGiB ?: template.memoryGiB)?.let { add("--memory"); add(fmt(it)) }
                (request.diskGiB ?: template.diskGiB)?.let { add("--disk"); add(fmt(it)) }
            }
            return CreateVmPreview(args, null, warnings)
        }
        val (locator, yaml) = locatorAndYaml(template, request)
        if (template.arch == "x86_64") warnings += "x86_64 on Apple Silicon is emulated and slow without Rosetta (VZ)."
        if (inventory.snapshot.value.vms.any { it.source == VmSource.LIMA && it.name == request.name && it.home == null }) {
            warnings += "A VM named ${request.name} already exists."
        }
        val command = lima().createCommand(request.name, locator ?: "/path/to/${request.name}.yaml", flags(template, request))
        return CreateVmPreview(command, yaml, warnings)
    }

    /** Runs `limactl create` as an action. The VM is left stopped. */
    fun create(id: String, request: CreateVmRequest, principal: Principal): String {
        val template = get(id)
        validate(request)
        if (template.colima) conflict("Colima templates are preview-only: Colima would start the VM")
        val ref = VmRef(VmSource.LIMA, request.name)
        if (inventory.find(ref) != null) conflict("A VM named ${request.name} already exists")
        val (locator, yaml) = locatorAndYaml(template, request)
        val file = yaml?.let { File(paths.tmp, "create-${request.name}-${clock()}.yaml").also { f -> writePrivate(f, it) } }
        val lima = lima()
        return actions.launch("vm.create", ref.targetId, principal, mapOf("template" to id)) { progress ->
            try {
                lima.create(request.name, file?.absolutePath ?: locator!!, flags(template, request), progress)
                inventory.refresh()
                "Created ${request.name} (stopped)"
            } finally {
                file?.delete()
            }
        }
    }

    /**
     * A plain Lima template is passed by locator. Custom YAML and provision snippets need a file: the
     * snippet goes into a small YAML that uses `base:` to inherit the template, so Lima merges them.
     */
    private fun locatorAndYaml(template: VmTemplate, request: CreateVmRequest): Pair<String?, String?> {
        val provision = request.provision?.takeIf { it.isNotBlank() }
        return when {
            template.yaml != null && template.base == "custom" -> {
                val extra = provision?.let { "\n" + provisionYaml(it) }.orEmpty()
                null to template.yaml + extra
            }
            template.yaml != null || provision != null -> {
                val body = buildString {
                    append("base:\n- ").append(template.base).append('\n')
                    template.yaml?.let { append(it.trimEnd()).append('\n') }
                    provision?.let { append(provisionYaml(it)) }
                }
                null to body
            }
            else -> template.base to null
        }
    }

    private fun provisionYaml(script: String): String = buildString {
        append("provision:\n- mode: system\n  script: |\n")
        script.lines().forEach { append("    ").append(it).append('\n') }
    }

    private fun flags(template: VmTemplate, request: CreateVmRequest) = CreateFlags(
        cpus = request.cpus ?: template.cpus,
        memoryGiB = request.memoryGiB ?: template.memoryGiB,
        diskGiB = request.diskGiB ?: template.diskGiB,
        mounts = request.mounts,
        portForwards = request.portForwards,
        arch = template.arch,
    )

    private fun validate(request: CreateVmRequest) {
        require(Names.isValidVmName(request.name)) { "Invalid VM name" }
        require((request.provision?.length ?: 0) <= 64 * 1024) { "Provision script is larger than 64 KB" }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    companion object {
        private const val MAX_YAML = 256 * 1024

        val BUILT_IN = listOf(
            VmTemplate("docker", "Docker host", "template://docker", "Rootless Docker with the socket forwarded to the host", builtIn = true),
            VmTemplate("colima-default", "Colima default", "colima", "Docker runtime, 2 CPU, 4 GiB (preview only)", builtIn = true, cpus = 2, memoryGiB = 4.0, colima = true),
            VmTemplate("k3s", "k3s", "template://k3s", "k3s with the kubeconfig copied to the host", builtIn = true),
            VmTemplate(
                "ubuntu-dev", "Ubuntu dev", "template://ubuntu-lts", "build-essential, git and mise", builtIn = true,
                yaml = "provision:\n- mode: system\n  script: |\n    #!/bin/sh\n    set -eux\n    export DEBIAN_FRONTEND=noninteractive\n    apt-get update\n    apt-get install -y build-essential git curl\n- mode: user\n  script: |\n    #!/bin/sh\n    set -eux\n    command -v mise || curl -fsSL https://mise.run | sh\n",
            ),
            VmTemplate("alpine", "Alpine minimal", "template://alpine", "Smallest footprint", builtIn = true),
            VmTemplate("x86_64", "x86_64 on Apple Silicon", "template://ubuntu-lts", "Ubuntu LTS emulated as x86_64 (Rosetta with VZ, otherwise QEMU)", builtIn = true, arch = "x86_64"),
            VmTemplate("podman", "Podman", "template://podman", "Podman with the socket forwarded", builtIn = true),
        )
    }
}
