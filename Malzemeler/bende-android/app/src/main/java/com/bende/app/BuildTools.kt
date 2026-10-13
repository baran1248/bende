package com.bende.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * GitHub entegrasyonu ve bulut derlemesi araçları.
 * Sözleşme WorkspaceTools.AgentTools ile aynıdır:
 * openAiDefinitions / anthropicDefinitions / needsApproval / execute.
 */
object BuildTools {
    const val WORKFLOW_PATH = ".github/workflows/bende-android.yml"

    /** Depoda yoksa otomatik eklenen bulut derleme iş akışı. */
    val WORKFLOW_YAML: String = """
        name: Bende Android derlemesi
        on:
          workflow_dispatch:
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - name: Java 21 kur
                uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: "21"
              - name: Android SDK kur
                uses: android-actions/setup-android@v3
              - name: Android platform 37 kur
                run: sdkmanager "platforms;android-37"
              - name: Gradle wrapper'a izin ver
                run: chmod +x gradlew
              - name: APK derle
                run: ./gradlew :app:assembleDebug
              - name: APK artifact olarak yükle
                uses: actions/upload-artifact@v4
                with:
                  name: bende-apk
                  path: app/build/outputs/apk/debug/*.apk
                  if-no-files-found: error
    """.trimIndent()

    private fun function(name: String, description: String, properties: JSONObject, required: JSONArray): JSONObject =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", required)
                .put("additionalProperties", false)))

    private fun tool(name: String, description: String, properties: JSONObject, required: JSONArray): JSONObject =
        JSONObject().put("name", name).put("description", description)
            .put("input_schema", JSONObject().put("type", "object").put("properties", properties).put("required", required)
                .put("additionalProperties", false))

    fun openAiDefinitions(): JSONArray = JSONArray().apply {
        put(function("github_push_project",
            "Upload the selected project folder to its GitHub repository as one commit. Creates the repository and the Android build workflow if missing. Honors the project's .gitignore. Requires user approval.",
            JSONObject().put("message", JSONObject().put("type", "string").put("description", "Optional commit message")),
            JSONArray()))
        put(function("build_start",
            "Start a cloud build by dispatching the Android workflow on GitHub Actions. Requires user approval.",
            JSONObject().put("ref", JSONObject().put("type", "string").put("description", "Optional branch name, default main")),
            JSONArray()))
        put(function("build_status",
            "Report the latest GitHub Actions runs (status and conclusion) for the repository.",
            JSONObject(), JSONArray()))
        put(function("build_log",
            "Show the tail of the log of the latest (or given) GitHub Actions run.",
            JSONObject().put("run_id", JSONObject().put("type", "integer").put("description", "Optional run id; defaults to the latest run")),
            JSONArray()))
        put(function("build_download",
            "Download the APK artifact of the latest successful cloud build and save it to the device Downloads folder. Requires user approval.",
            JSONObject(), JSONArray()))
    }

    fun anthropicDefinitions(): JSONArray = JSONArray().apply {
        put(tool("github_push_project",
            "Upload the selected project folder to its GitHub repository as one commit. Creates the repository and the Android build workflow if missing. Honors the project's .gitignore. Requires user approval.",
            JSONObject().put("message", JSONObject().put("type", "string").put("description", "Optional commit message")),
            JSONArray()))
        put(tool("build_start",
            "Start a cloud build by dispatching the Android workflow on GitHub Actions. Requires user approval.",
            JSONObject().put("ref", JSONObject().put("type", "string").put("description", "Optional branch name, default main")),
            JSONArray()))
        put(tool("build_status",
            "Report the latest GitHub Actions runs (status and conclusion) for the repository.",
            JSONObject(), JSONArray()))
        put(tool("build_log",
            "Show the tail of the log of the latest (or given) GitHub Actions run.",
            JSONObject().put("run_id", JSONObject().put("type", "integer").put("description", "Optional run id; defaults to the latest run")),
            JSONArray()))
        put(tool("build_download",
            "Download the APK artifact of the latest successful cloud build and save it to the device Downloads folder. Requires user approval.",
            JSONObject(), JSONArray()))
    }

    fun needsApproval(name: String): Boolean = name in setOf("github_push_project", "build_start", "build_download")

    fun display(invocation: ToolInvocation): String = when (invocation.name) {
        "github_push_project" -> "github_push_project: proje GitHub'a iletilecek"
        "build_start" -> "build_start: bulut derlemesi başlatılacak"
        "build_download" -> "build_download: APK indirilecek"
        else -> "${invocation.name}: ${invocation.arguments.optString("run_id")}"
    }

    suspend fun execute(context: Context, invocation: ToolInvocation): String = withContext(Dispatchers.IO) {
        when (invocation.name) {
            "github_push_project" -> pushProject(context, invocation.arguments.optString("message"))
            "build_start" -> buildStart(context, invocation.arguments.optString("ref"))
            "build_status" -> buildStatus(context)
            "build_log" -> buildLog(context, invocation.arguments.optLong("run_id", 0L))
            "build_download" -> buildDownload(context)
            else -> throw IllegalArgumentException("Desteklenmeyen araç: ${invocation.name}")
        }
    }

    /** APK dosyasını cihazın İndirilenler klasörüne kaydeder; dönen değer dosyanın content:// URI'si. */
    fun saveApkToDownloads(context: Context, fileName: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "İndirilecek APK boş görünüyor." }
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "bende-app-debug.apk" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("İndirilenler klasörüne kayıt açılamadı.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("APK cihaza yazılamadı.")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (error: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
            return uri.toString()
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueFile(dir, safeName)
        FileOutputStream(target).use { it.write(bytes) }
        return Uri.fromFile(target).toString()
    }

    // ------------------------------------------------------------------
    // Araç uygulamaları
    // ------------------------------------------------------------------

    private suspend fun pushProject(context: Context, message: String): String {
        ensureToken(context)
        val config = GitHubSettings.load(context)
        require(config.name.isNotBlank()) { "GitHub ekranından depo adı girin." }
        val rootUri = ProjectWorkspace.uri(context)
            ?: throw IllegalStateException("Önce çalışma alanı bölümünden bir proje klasörü seçin.")
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("Seçilen proje klasörüne erişilemiyor. Klasörü yeniden seçin.")
        if (!root.isDirectory) throw IllegalStateException("Seçilen öğe bir klasör değil.")

        val collected = collectProjectFiles(context, root)
        require(collected.files.isNotEmpty()) { "Projede iletilecek dosya bulunamadı (.gitignore kuralları her şeyi eliyor olabilir)." }

        val owner = GitHubApi.user(context)
        val repoInfo = if (GitHubApi.repoExists(context, owner, config.name)) {
            "Depo mevcut: $owner/${config.name}"
        } else {
            GitHubApi.createOrUpdateRepo(context, config.name, config.isPrivate).let { "Depo oluşturuldu: $it" }
        }

        val files = collected.files.toMutableList()
        var workflowAdded = false
        if (files.none { it.path == WORKFLOW_PATH } && !GitHubApi.fileExists(context, owner, config.name, WORKFLOW_PATH)) {
            files.add(GitHubFile(WORKFLOW_PATH, WORKFLOW_YAML.toByteArray(Charsets.UTF_8)))
            workflowAdded = true
        }

        val commitMessage = message.trim().ifBlank { "Bende: proje iletimi (${files.size} dosya)" }
        val sha = GitHubApi.pushFiles(context, owner, config.name, commitMessage, files, config.branch)

        val sizeKb = files.sumOf { it.bytes.size } / 1024
        return buildString {
            append("GitHub'a iletildi: $owner/${config.name} @ ${config.branch}\n")
            append("$repoInfo\n")
            append("Commit: $sha\n")
            append("Dosya: ${files.size} ($sizeKb KB)\n")
            if (workflowAdded) append("Bulut derleme iş akışı eklendi: $WORKFLOW_PATH\n")
            if (collected.warnings.isNotEmpty()) append(collected.warnings.joinToString("\n"))
        }.trim()
    }

    private suspend fun buildStart(context: Context, ref: String): String {
        ensureToken(context)
        val config = GitHubSettings.load(context)
        require(config.name.isNotBlank()) { "GitHub ekranından depo adı girin." }
        val owner = GitHubApi.user(context)
        if (!GitHubApi.repoExists(context, owner, config.name)) {
            throw IllegalStateException("Depo bulunamadı ($owner/${config.name}). Önce github_push_project ile projeyi iletip depoyu oluşturun.")
        }
        if (!GitHubApi.fileExists(context, owner, config.name, WORKFLOW_PATH)) {
            throw IllegalStateException("İş akışı dosyası yok ($WORKFLOW_PATH). Önce github_push_project ile projeyi iletip iş akışını ekleyin.")
        }
        val branch = ref.trim().ifBlank { config.branch }
        return GitHubApi.triggerWorkflow(context, owner, config.name, WORKFLOW_PATH, branch) +
            "\nDurumu 'build_status' ile, logu 'build_log' ile izleyebilirsiniz."
    }

    private suspend fun buildStatus(context: Context): String {
        ensureToken(context)
        val config = GitHubSettings.load(context)
        require(config.name.isNotBlank()) { "GitHub ekranından depo adı girin." }
        val owner = GitHubApi.user(context)
        val runs = GitHubApi.listRuns(context, owner, config.name)
        if (runs.isEmpty()) return "$owner/${config.name} için henüz derleme çalışması yok. 'build_start' ile başlatabilirsiniz."
        val latest = runs.first()
        return buildString {
            append("Son çalışma: #${latest.id} — ${runStatusText(latest)} — ${latest.displayTitle}\n")
            append("Dal: ${latest.headBranch ?: "-"}  Oluşturulma: ${latest.createdAt}\n")
            append("Son çalışmalar:\n")
            runs.take(10).forEach { run ->
                append("  #${run.id}  ${runStatusText(run)}  ${run.displayTitle.take(60)}\n")
            }
        }.trim()
    }

    private suspend fun buildLog(context: Context, runId: Long): String {
        ensureToken(context)
        val config = GitHubSettings.load(context)
        require(config.name.isNotBlank()) { "GitHub ekranından depo adı girin." }
        val owner = GitHubApi.user(context)
        val targetId = if (runId > 0) runId else {
            val runs = GitHubApi.listRuns(context, owner, config.name)
            runs.firstOrNull()?.id ?: throw IllegalStateException("Henüz derleme çalışması yok.")
        }
        val run = GitHubApi.getRun(context, owner, config.name, targetId)
        val logs = GitHubApi.getRunLogs(context, owner, config.name, targetId)
        val tail = logs.takeLast(16_000)
        return "Run #${run.id} — ${runStatusText(run)} — ${run.displayTitle}\n\n$tail"
    }

    private suspend fun buildDownload(context: Context): String {
        ensureToken(context)
        val config = GitHubSettings.load(context)
        require(config.name.isNotBlank()) { "GitHub ekranından depo adı girin." }
        val owner = GitHubApi.user(context)
        val bytes = GitHubApi.downloadLatestArtifactApk(context, owner, config.name)
        val fileName = "bende-${config.name}-debug.apk"
        val uri = saveApkToDownloads(context, fileName, bytes)
        return "APK indirildi ve cihaza kaydedildi.\nDosya: $fileName\nKonum: İndirilenler (Downloads) klasörü\nURI: $uri\nBoyut: ${bytes.size / 1024} KB"
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    private fun ensureToken(context: Context) {
        if (!GitHubAuth.hasToken(context)) {
            throw IllegalStateException("GitHub anahtarı girilmemiş. GitHub ekranından PAT ile veya cihaz akışıyla giriş yapın.")
        }
    }

    private fun runStatusText(run: WorkflowRun): String = when (run.status) {
        "queued" -> "Sırada"
        "in_progress" -> "Çalışıyor"
        "completed" -> when (run.conclusion) {
            "success" -> "Başarılı"
            "failure" -> "Başarısız"
            "cancelled" -> "İptal edildi"
            "timed_out" -> "Zaman aşımı"
            "neutral" -> "Nötr"
            "skipped" -> "Atlandı"
            "action_required" -> "Eylem gerekli"
            else -> "Tamamlandı"
        }
        else -> run.status
    }

    private class Collected(val files: List<GitHubFile>, val warnings: List<String>)

    private fun collectProjectFiles(context: Context, root: DocumentFile): Collected {
        val ignoreText = root.findFile(".gitignore")?.takeIf { it.isFile }?.let { file ->
            runCatching { context.contentResolver.openInputStream(file.uri)?.use { readBounded(it, 256 * 1024) } }.getOrNull()
                ?.let { String(it, Charsets.UTF_8) }
        }.orEmpty()
        val rules = parseGitignore(ignoreText)

        val files = mutableListOf<GitHubFile>()
        var skippedRule = 0
        var skippedSensitive = 0
        var skippedLarge = 0
        var totalBytes = 0L
        var stoppedEarly = false

        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
            if (depth > 12 || stoppedEarly) return
            for (child in dir.listFiles()) {
                if (stoppedEarly) return
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (rel == ".git" || rel.startsWith(".git/")) continue
                if (isSensitiveName(name)) {
                    if (child.isFile) skippedSensitive++
                    continue
                }
                if (isIgnored(rel, child.isDirectory, rules)) {
                    skippedRule++
                    continue
                }
                if (child.isDirectory) {
                    walk(child, rel, depth + 1)
                    continue
                }
                if (!child.isFile) continue
                if (files.size >= 500) {
                    stoppedEarly = true
                    return
                }
                val size = child.length()
                if (size > 5L * 1024 * 1024) {
                    skippedLarge++
                    continue
                }
                val bytes = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { readBounded(it, 5 * 1024 * 1024 + 1) }
                }.getOrNull()
                if (bytes == null || bytes.size > 5 * 1024 * 1024) {
                    skippedLarge++
                    continue
                }
                if (totalBytes + bytes.size > 25L * 1024 * 1024) {
                    stoppedEarly = true
                    return
                }
                totalBytes += bytes.size
                files.add(GitHubFile(rel, bytes))
            }
        }
        walk(root, "", 0)

        val warnings = mutableListOf<String>()
        if (skippedRule > 0) warnings.add("Atlandı: $skippedRule dosya/klasör .gitignore kurallarıyla elendi.")
        if (skippedSensitive > 0) warnings.add("Atlandı: $skippedSensitive gizli/anahtar dosyası iletilemedi.")
        if (skippedLarge > 0) warnings.add("Atlandı: $skippedLarge dosya 5 MB tekil sınırını aşıyor.")
        if (stoppedEarly) warnings.add("Uyarı: 500 dosya / 25 MB sınırına ulaşıldı; kalan dosyalar iletilemedi.")
        return Collected(files, warnings)
    }

    private data class IgnoreRule(val regex: Regex, val dirOnly: Boolean, val negated: Boolean)

    private fun parseGitignore(text: String): List<IgnoreRule> = text.lineSequence().mapNotNull { raw ->
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        var negated = false
        if (line.startsWith("!")) {
            negated = true
            line = line.drop(1)
        }
        var dirOnly = false
        if (line.endsWith("/")) {
            dirOnly = true
            line = line.dropLast(1)
        }
        if (line.isEmpty()) return@mapNotNull null
        val anchored = line.startsWith("/") || line.drop(1).contains('/')
        val pattern = line.removePrefix("/")
        val source = if (anchored) globToRegexSource(pattern) else "(?:.*/)?${globToRegexSource(pattern)}"
        IgnoreRule(Regex(source), dirOnly, negated)
    }.toList()

    private fun globToRegexSource(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < glob.length && glob[i] == '/') i++
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                c == '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end == -1) {
                        sb.append(Regex.escape("["))
                        i++
                    } else {
                        sb.append(glob.substring(i, end + 1))
                        i = end + 1
                    }
                }
                else -> {
                    sb.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun isIgnored(relPath: String, isDir: Boolean, rules: List<IgnoreRule>): Boolean {
        val segments = relPath.split('/')
        var ignored = false
        for (rule in rules) {
            for (i in segments.indices) {
                val prefix = segments.subList(0, i + 1).joinToString("/")
                val prefixIsDir = i < segments.lastIndex || isDir
                if (rule.dirOnly && !prefixIsDir) continue
                if (rule.regex.matches(prefix)) {
                    ignored = !rule.negated
                    break
                }
            }
        }
        return ignored
    }

    private fun isSensitiveName(name: String): Boolean {
        val n = name.lowercase()
        return n == ".env" || n.startsWith(".env.") || n.endsWith(".jks") || n.endsWith(".keystore") ||
            n.contains("credential") || n.contains("secret") || n.contains("id_rsa") || n.contains("id_ed25519")
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', "")
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != name }
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, if (extension == null) "$base ($index)" else "$base ($index).$extension")
            index++
        }
        return candidate
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total <= maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }
}