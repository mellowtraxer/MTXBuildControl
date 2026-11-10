package de.ricci.buildcontrol

import org.json.JSONObject
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

data class Project(val repo: String, val branch: String, val name: String = repo.substringAfter('/'), val id: Long? = null, val archived: Boolean = false) {
    init { require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo)) { "Repository als Besitzer/Name angeben." }; require(branch.isNotBlank()) }
}
data class Runner(val name: String, val os: String, val status: String, val busy: Boolean, val labels: Set<String>)
data class RemoteFile(val text: String, val sha: String)
data class Workflow(val id: Long, val name: String, val path: String, val dispatch: Map<String, Input>)
data class Input(val required: Boolean, val default: String, val options: List<String>, val type: String)
data class Build(val id: Long, val number: Long, val name: String, val status: String, val conclusion: String, val branch: String, val sha: String, val url: String, val started: String, val updated: String, val attempt: Int = 1) {
    val label get() = when { status == "queued" || status == "waiting" || status == "pending" -> "Wartend"; status != "completed" -> "Läuft"; conclusion == "success" -> "Erfolgreich"; conclusion == "failure" -> "Fehlgeschlagen"; else -> conclusion }
}
data class AppUpdate(val asset: PackageAsset, val versionCode: Int, val notes: String)

data class PackageAsset(val title: String, val apiPath: String, val zip: Boolean, val version: String, val digest: String = "")
data class Snapshot(val project: Project, val mode: String?, val workflows: List<Workflow>, val runners: List<Runner>, val runnerError: String?, val builds: List<Build>, val assets: List<PackageAsset>, val notes: List<String>)

object RunnerPolicy {
    const val PATH = ".github/mtx-runner.json"
    val labels = setOf("self-hosted", "windows", "x64", "ricci")
    fun mode(text: String): String = JSONObject(text).getString("mode").also { require(it in setOf("pc", "github")) { "Ungültige Runner-Konfiguration; keine Änderung." } }
    fun validatePc(runners: List<Runner>) {
        val matches = runners.filter { it.name == "RICCI" && it.os == "windows" && it.status == "online" }
        require(matches.size == 1) { "RICCI ist nicht eindeutig online. PC starten oder GITHUB RUNNER – CLOUD wählen." }
        require(matches.single().labels.map { it.lowercase() }.containsAll(labels)) { "RICCI benötigt die Labels self-hosted, Windows, X64 und RICCI." }
        require(runners.none { it.name != "RICCI" && it.labels.map { l -> l.lowercase() }.containsAll(labels) }) { "Ein anderer Runner besitzt ebenfalls das RICCI-Label." }
    }
    fun updated(text: String, target: String): String {
        mode(text); require(target in setOf("pc", "github"))
        return JSONObject(text).put("mode", target).toString() + "\n"
    }
}
object WorkflowReader {
    fun read(text: String): Map<*, *> = Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = 1000000 })).load<Map<*, *>>(text) ?: emptyMap<Any, Any>()
    fun inputs(text: String): Map<String, Input>? {
        val yaml = read(text)
        val on = yaml["on"] ?: yaml[true] // YAML 1.1 resolves unquoted on to true.
        if (on is String) return if (on == "workflow_dispatch") emptyMap() else null
        if (on is List<*>) return if ("workflow_dispatch" in on) emptyMap() else null
        val events = on as? Map<*, *> ?: return null
        if (!events.containsKey("workflow_dispatch")) return null
        val dispatch = events["workflow_dispatch"] as? Map<*, *> ?: return emptyMap()
        val inputs = dispatch["inputs"] as? Map<*, *> ?: return emptyMap()
        return inputs.entries.associate { (key, value) ->
            val m = value as? Map<*, *> ?: emptyMap<Any, Any>()
            key.toString() to Input(m["required"] == true, m["default"]?.toString() ?: "", (m["options"] as? List<*>)?.map { it.toString() } ?: emptyList(), m["type"]?.toString() ?: "string")
        }
    }
    fun wired(texts: List<String>): Boolean = texts.any { text ->
        val jobs = read(text)["jobs"] as? Map<*, *> ?: return@any false
        // Recognize the existing MTX JSON selector protocol, not an arbitrary runs-on replacement.
        text.contains(".github/mtx-runner.json") && jobs.values.any { v ->
            val job = v as? Map<*, *> ?: return@any false
            job["runs-on"]?.toString()?.contains("fromJSON(needs.select-runner.outputs.labels)") == true
        }
    }
}

/** Failure to read the administration-only runner list is not an offline signal. */
object RunnerStatus {
    fun message(runners: List<Runner>, error: String?): String {
        if(error!=null)return "PC-Status gerade nicht abrufbar. Du kannst die Auswahl trotzdem speichern."
        val ricci=runners.filter{it.name=="RICCI"}
        if(ricci.isEmpty())return "Für dieses Projekt ist kein PC bei GitHub gemeldet. PC-Builds warten, bis RICCI verbunden ist."
        if(ricci.any{it.status.equals("online",true)})return if(ricci.any{it.busy})"PC ist online und arbeitet. Laufende Builds laufen weiter." else "PC ist online."
        if(ricci.all{it.status.equals("offline",true)})return "PC ist bei GitHub offline gemeldet. Du kannst PC trotzdem speichern oder GitHub wählen."
        return "PC-Status gerade unbekannt. Du kannst die Auswahl trotzdem speichern."
    }
}
