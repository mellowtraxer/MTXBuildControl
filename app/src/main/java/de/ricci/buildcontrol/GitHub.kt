package de.ricci.buildcontrol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.io.File
import java.util.zip.ZipInputStream
import de.ricci.nexus.ota.AndroidOta

class ApiException(val status: Int, message: String) : Exception(message)
class GitHub(private val token: String, private val base: String = "https://api.github.com/") {
    private val client = OkHttpClient.Builder().followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    private fun request(path: String, method: String = "GET", body: JSONObject? = null, binary: Boolean = false): Request {
        require(!path.startsWith('/') && !path.contains("://"))
        val b = Request.Builder().url(base + path).header("Accept", if (binary) "application/octet-stream" else "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "MTXBuildControl")
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        if (method != "GET") b.method(method, (body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
        return b.build()
    }
    fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = client.newCall(request(path, method, body)).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw ApiException(r.code, when(r.code) {
            401 -> "GitHub-Anmeldung ungültig oder abgelaufen. Token erneuern."
            403 -> "GitHub verweigert diese Aktion. Token-Berechtigungen, Branchschutz oder API-Limit prüfen."
            404 -> "Nicht gefunden oder kein Zugriff: Repository, Branch und Token-Auswahl prüfen."
            409, 422 -> "GitHub hat die Änderung abgelehnt. Neu laden; Branchschutz und gleichzeitige Änderungen prüfen."
            else -> "GitHub HTTP ${r.code}: ${runCatching { JSONObject(text).optString("message") }.getOrDefault("Anfrage fehlgeschlagen")}" })
        if (text.isBlank()) JSONObject() else if (text.startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
    }
    fun list(path: String, field: String, limit: Int = Int.MAX_VALUE): List<JSONObject> {
        val out = mutableListOf<JSONObject>(); var page = 1
        while (true) {
            val a = json(path + (if ('?' in path) "&" else "?") + "per_page=100&page=$page").optJSONArray(field) ?: break
            for (i in 0 until a.length()) out += a.getJSONObject(i)
            if (a.length() < 100 || out.size >= limit || "/contents/" in path) break
            page++; require(page < 1000) { "Zu viele API-Seiten." }
        }
        return out.take(limit)
    }
    fun user() = json("user").getString("login")
    fun discoverProjects(owner: String): List<Project> {
        // Merge token-accessible repositories with public repositories: a newly
        // created public repo may not yet be included in a fine-grained token.
        val accessible = list("user/repos?affiliation=owner&sort=updated", "items")
        val public = list("users/${enc(owner)}/repos?type=owner&sort=updated", "items")
        return (accessible + public)
            .distinctBy { it.getLong("id") }
            .filter { it.getString("full_name").substringBefore('/').equals(owner, true) }
            .map { Project(it.getString("full_name"), it.getString("default_branch"), it.getString("name"),
                it.getLong("id"), it.optBoolean("archived")) }
    }
    fun defaultBranch(repo: String) = json("repos/$repo").getString("default_branch")
    fun branches(repo: String) = list("repos/$repo/branches", "items").map { it.getString("name") }
    fun file(p: Project, path: String): RemoteFile {
        val o = json("repos/${p.repo}/contents/$path?ref=${enc(p.branch)}")
        require(o.getString("encoding") == "base64")
        return RemoteFile(String(Base64.getMimeDecoder().decode(o.getString("content")), Charsets.UTF_8), o.getString("sha"))
    }
    fun runners(p: Project): List<Runner> = list("repos/${p.repo}/actions/runners", "runners").map { o ->
        val a = o.getJSONArray("labels")
        Runner(o.getString("name"), o.getString("os"), o.getString("status"), o.getBoolean("busy"), (0 until a.length()).map { a.getJSONObject(it).getString("name") }.toSet())
    }
    fun snapshot(p: Project): Snapshot {
        json("repos/${p.repo}/branches/${enc(p.branch)}")
        val notes = mutableListOf<String>()
        val registered = runCatching { list("repos/${p.repo}/actions/workflows", "workflows") }
            .onFailure { if(it is ApiException&&it.status==401)throw it; notes += "Workflow-Übersicht gerade nicht verfügbar: ${it.message}" }.getOrDefault(emptyList())
        val directory = try { list("repos/${p.repo}/contents/.github/workflows?ref=${enc(p.branch)}", "items") }
            catch(e: ApiException) { if(e.status == 404) { notes += "Keine Workflow-Dateien auf diesem Branch."; emptyList() } else throw e }
        val files = directory.filter { it.optString("name").endsWith(".yml") || it.optString("name").endsWith(".yaml") }
        val texts = mutableListOf<String>()
        val workflows = files.mapNotNull { o ->
            try {
                val path = o.getString("path"); val text = file(p, path).text
                val inputs = WorkflowReader.inputs(text)
                val name = WorkflowReader.read(text)["name"]?.toString() ?: o.getString("name")
                texts += text
                Workflow(registered.firstOrNull { it.optString("path") == path }?.optLong("id") ?: 0, name, path, inputs ?: emptyMap()).let { it to (inputs != null) }
            } catch(e: Exception) { notes += "Workflow ${o.optString("name")}: ${e.message}"; null }
        }
        dispatchable = workflows.filter { it.second }.map { it.first.path }.toSet()
        val mode = try {
            val raw = RunnerPolicy.mode(file(p, RunnerPolicy.PATH).text)
            if (WorkflowReader.wired(texts)) raw else { notes += "Runner-Datei vorhanden, aber kein kompatibler Auswahl-Workflow erkannt."; null }
        } catch(e: ApiException) { if (e.status != 404) notes += e.message.orEmpty(); null }
        val runnerResult = runCatching { runners(p) }
        val builds = runCatching { list("repos/${p.repo}/actions/runs?branch=${enc(p.branch)}&exclude_pull_requests=true", "workflow_runs", 30).take(30).map { o -> Build(o.getLong("id"),o.getLong("run_number"),o.optString("name"),o.getString("status"),o.optString("conclusion"),o.optString("head_branch"),o.getString("head_sha"),o.getString("html_url"),o.optString("run_started_at"),o.getString("updated_at"), o.optInt("run_attempt", 1)) } }
            .onFailure { if(it is ApiException&&it.status==401)throw it; notes += "Build-Übersicht gerade nicht verfügbar: ${it.message}" }.getOrDefault(emptyList())
        val assets = mutableListOf<PackageAsset>()
        runCatching {
            list("repos/${p.repo}/releases", "items", 20).take(20).forEach { release ->
                val a = release.getJSONArray("assets")
                for (i in 0 until a.length()) { val asset = a.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) assets += PackageAsset(asset.getString("name"), "repos/${p.repo}/releases/assets/${asset.getLong("id")}",false,release.getString("tag_name"),asset.optString("digest"))
                }
            }
        }.onFailure { notes += "Releases: ${it.message}" }
        // Artifacts belong to the selected branch's runs, never an unrelated branch.
        builds.take(5).forEach { build -> runCatching {
            list("repos/${p.repo}/actions/runs/${build.id}/artifacts", "artifacts").filter { !it.optBoolean("expired") }.forEach {
                assets += PackageAsset(it.getString("name"),"repos/${p.repo}/actions/artifacts/${it.getLong("id")}/zip",true,"Build #${build.number} · ${build.sha.take(7)}")
            }
        }.onFailure { notes += "Artefakte #${build.number}: ${it.message}" } }
        return Snapshot(p,mode,workflows.map { it.first },runnerResult.getOrDefault(emptyList()),runnerResult.exceptionOrNull()?.message,
            builds,assets,notes,texts.any(WorkflowReader::automaticRouting))
    }
    var dispatchable: Set<String> = emptySet(); private set
    fun switch(p: Project, target: String): String {
        // Re-read both workflow wiring and config immediately before each confirmed write.
        val paths = list("repos/${p.repo}/contents/.github/workflows?ref=${enc(p.branch)}", "items").map { it.getString("path") }.filter { it.endsWith(".yml") || it.endsWith(".yaml") }
        require(WorkflowReader.wired(paths.map { file(p, it).text })) { "Dieses Projekt unterstützt den MTX-Runner-Vertrag noch nicht." }
        val current = file(p,RunnerPolicy.PATH)
        val oldMode = RunnerPolicy.mode(current.text)
        require(target in setOf("pc", "github"))
        // Routing is a persistent preference, not a request to start/stop the PC.
        // Runner-list access requires separate administration permission and must
        // never prevent saving this preference. GitHub schedules the existing
        // label selector; workflow identity checks still require Windows RICCI.
        if (oldMode == target) return "Bereits im Modus $target. Kein Commit erstellt."
        val result = json("repos/${p.repo}/contents/${RunnerPolicy.PATH}", "PUT",JSONObject().put("branch",p.branch).put("sha",current.sha).put("message","ci: route future MTX builds to $target runner").put("content",Base64.getEncoder().encodeToString(RunnerPolicy.updated(current.text,target).toByteArray())))
        val sha = result.getJSONObject("commit").getString("sha")
        // Verify the immutable commit we just created. A branch read immediately
        // after PUT can still expose an older revision through GitHub caches.
        val verified = RunnerPolicy.mode(file(p.copy(branch=sha),RunnerPolicy.PATH).text)
        require(verified == target) { "Commit $sha erstellt, aber Inhalt konnte nicht bestätigt werden. Bitte neu laden." }
        return "Modus $target bestätigt · Commit ${sha.take(12)}. Bestehende Builds laufen weiter."
    }
    fun dispatch(p: Project, workflow: Workflow, inputs: Map<String,String>) {
        val allowed = WorkflowReader.inputs(file(p,workflow.path).text) ?: error("Workflow unterstützt keinen manuellen Start.")
        require(inputs.keys.all { it in allowed })
        allowed.forEach { (k,v) ->
            val value = inputs[k] ?: v.default
            require(!v.required || value.isNotBlank()) { "Pflichtfeld: $k" }
            require(v.options.isEmpty() || value in v.options) { "Ungültige Auswahl für $k" }
            require(v.type != "boolean" || value in setOf("true", "false")) { "Für $k true oder false eingeben." }
        }
        json("repos/${p.repo}/actions/workflows/${workflow.path.substringAfterLast('/')}/dispatches", "POST",JSONObject().put("ref",p.branch).put("inputs",JSONObject(inputs)))
    }
    fun failure(p: Project, build: Build): String {
        val jobs = list("repos/${p.repo}/actions/runs/${build.id}/jobs", "jobs")
        return jobs.filter { it.optString("conclusion") == "failure" }.joinToString("\n\n") { j ->
            val steps = j.optJSONArray("steps") ?: JSONArray()
            val failed = (0 until steps.length()).map { steps.getJSONObject(it) }.filter { it.optString("conclusion") == "failure" }.joinToString { it.getString("name") }
            val check = j.optString("check_run_url")
            val annotation = if (check.startsWith("https://api.github.com/repos/${p.repo}/check-runs/")) runCatching { list(check.removePrefix("https://api.github.com/") + "/annotations","items").take(20).joinToString("\n") { it.optString("message") } }.getOrDefault("") else ""
            "${j.getString("name")}: $failed\n$annotation"
        }.ifBlank { "Keine Fehlerannotation verfügbar. Vollständige Logs über den GitHub-Link öffnen." }
    }
    fun buildJobs(p: Project, build: Build): String =
        list("repos/${p.repo}/actions/runs/${build.id}/attempts/${build.attempt}/jobs", "jobs")
            .joinToString("\n") { job ->
                val runner = job.optString("runner_name", "").ifBlank { "Runner nicht gemeldet" }
                val status = if (job.isNull("conclusion")) job.optString("status") else job.optString("conclusion")
                "${job.getString("name")} · $runner · $status"
            }.ifBlank { "GitHub meldet noch keine Jobs." }

    fun cancel(p: Project, build: Build) {
        json("repos/${p.repo}/actions/runs/${build.id}/cancel", "POST")
    }

    fun ownUpdate(installedCode: Int): AppUpdate? {
        val repo = "mellowtraxer/MTXBuildControl"
        return list("repos/$repo/releases", "items", 20).asSequence().mapNotNull { release ->
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@mapNotNull null
            val tag = release.optString("tag_name")
            val version = Regex("build-([1-9][0-9]*)").matchEntire(tag)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (version <= installedCode) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull {
                it.optString("name") == "MTXBuildControl.apk" &&
                    it.optString("state") == "uploaded" &&
                    it.optLong("size") in 1..MAX_APK_SIZE &&
                    it.optString("digest").matches(Regex("sha256:[0-9a-f]{64}"))
            } ?: return@mapNotNull null
            AppUpdate(PackageAsset("MTXBuildControl.apk", "repos/$repo/releases/assets/${apk.getLong("id")}",
                false, tag, apk.getString("digest")), version, release.optString("body", "").take(4000), apk.getLong("size"))
        }.maxByOrNull { it.versionCode }
    }

    fun download(asset: PackageAsset, folder: File, progress: ((Long, Long) -> Unit)? = null): List<File> {
        folder.mkdirs()
        require(folder.usableSpace > 0) { "Kein Speicher für den Download verfügbar." }
        val destination = File(folder,"download-${System.nanoTime()}")
        try {
            client.newCall(request(asset.apiPath,binary=true)).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code,"APK-Download fehlgeschlagen (HTTP ${r.code}).")
                val body = r.body ?: error("Der Download ist leer.")
                val length = body.contentLength()
                require(length <= MAX_APK_SIZE) { "Download zu groß." }
                require(length <= folder.usableSpace) { "Nicht genügend Speicherplatz für die APK." }
                body.byteStream().use { source ->
                    destination.outputStream().use { out ->
                        val copied = copyBounded(source, out) { bytes -> progress?.invoke(bytes, length) }
                        require(copied > 0 && (length < 0 || copied == length)) { "Download unvollständig." }
                    }
                }
            }
            if (!asset.zip) {
                if (asset.digest.startsWith("sha256:")) {
                    AndroidOta.verifySha256(destination, asset.digest.removePrefix("sha256:"))
                }
                val apk=File(folder,"package-${System.nanoTime()}.apk"); check(destination.renameTo(apk)); return listOf(apk)
            }
            val out = mutableListOf<File>(); var total = 0L
            try {
                ZipInputStream(destination.inputStream()).use { zip ->
                    var entries=0
                    while(true) { val entry=zip.nextEntry ?: break; require(++entries<=1000)
                        if (!entry.isDirectory && entry.name.endsWith(".apk",true)) {
                            val apk=File(folder,"package-${System.nanoTime()}.apk")
                            out += apk
                            apk.outputStream().use { total += copyBounded(zip,it,300L*1024*1024-total) }
                        } else {
                            val sink = object : java.io.OutputStream() {
                                override fun write(value: Int) {}
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {}
                            }
                            total += copyBounded(zip, sink, 300L*1024*1024-total)
                        }
                        zip.closeEntry()
                    }
                }
                require(out.isNotEmpty()) { "Dieses Artefakt enthält keine APK." }; return out
            } catch(e:Exception) { out.forEach { it.delete() }; throw e }
        } finally { destination.delete() }
    }
    private fun copyBounded(source: java.io.InputStream, out: java.io.OutputStream, max: Long = MAX_APK_SIZE, progress: (Long) -> Unit = {}): Long {
        var total=0L; val buf=ByteArray(65536)
        while(true){val n=source.read(buf);if(n<0)break;total+=n;require(total<=max){"Download zu groß."};out.write(buf,0,n);progress(total)};return total
    }
    companion object { private const val MAX_APK_SIZE = 300L * 1024 * 1024; fun enc(v: String): String = URLEncoder.encode(v,"UTF-8").replace("+","%20") }
}
