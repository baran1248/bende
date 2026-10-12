package com.bende.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ToolInvocation(val id: String, val name: String, val arguments: JSONObject)
data class ToolOutput(val text: String, val needsApproval: Boolean, val display: String)

object ProjectWorkspace {
    private const val PREFS = "workspace"

    fun uri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree_uri", null)?.let(Uri::parse)
    fun name(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree_name", null)

    fun save(context: Context, uri: Uri, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).putString("tree_name", name).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

object AgentTools {
    private val definitions = listOf(
        Triple("read_file", "Read a UTF-8 text file in the selected project. Never use this for secrets or binary files.", "path"),
        Triple("search_files", "Search project text files for a literal query. Optionally limit the search to a relative folder.", "query"),
        Triple("write_file", "Create or replace a UTF-8 text file in the selected project. Requires user approval.", "content"),
        Triple("edit_file", "Replace one exact text block in an existing UTF-8 project file. Requires user approval.", "new_text"),
        Triple("delete_file", "Delete one file in the selected project. Requires user approval.", "path")
    )

    fun openAiDefinitions(): JSONArray = JSONArray().apply {
        definitions.forEach { (name, description, _) ->
            val properties = JSONObject()
            when (name) {
                "read_file", "delete_file" -> properties.put("path", JSONObject().put("type", "string").put("description", "Relative path from project root"))
                "search_files" -> { properties.put("query", JSONObject().put("type", "string")); properties.put("path", JSONObject().put("type", "string").put("description", "Optional relative directory")) }
                "write_file" -> { properties.put("path", JSONObject().put("type", "string")); properties.put("content", JSONObject().put("type", "string")) }
                "edit_file" -> { properties.put("path", JSONObject().put("type", "string")); properties.put("old_text", JSONObject().put("type", "string")); properties.put("new_text", JSONObject().put("type", "string")) }
            }
            val required = when (name) {
                "search_files" -> JSONArray().put("query")
                "write_file" -> JSONArray().put("path").put("content")
                "edit_file" -> JSONArray().put("path").put("old_text").put("new_text")
                else -> JSONArray().put("path")
            }
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", required)
                    .put("additionalProperties", false))))
        }
    }

    fun anthropicDefinitions(): JSONArray = JSONArray().apply {
        definitions.forEach { (name, description, _) ->
            val props = JSONObject()
            val required = JSONArray()
            when (name) {
                "read_file", "delete_file" -> { props.put("path", JSONObject().put("type", "string")); required.put("path") }
                "search_files" -> { props.put("query", JSONObject().put("type", "string")); props.put("path", JSONObject().put("type", "string")); required.put("query") }
                "write_file" -> { props.put("path", JSONObject().put("type", "string")); props.put("content", JSONObject().put("type", "string")); required.put("path").put("content") }
                "edit_file" -> { props.put("path", JSONObject().put("type", "string")); props.put("old_text", JSONObject().put("type", "string")); props.put("new_text", JSONObject().put("type", "string")); required.put("path").put("old_text").put("new_text") }
            }
            put(JSONObject().put("name", name).put("description", description).put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", required).put("additionalProperties", false)))
        }
    }

    fun needsApproval(name: String): Boolean = name in setOf("write_file", "edit_file", "delete_file")

    fun display(invocation: ToolInvocation): String = when (invocation.name) {
        "search_files" -> "${invocation.name}: ${invocation.arguments.optString("query")}"
        else -> "${invocation.name}: ${invocation.arguments.optString("path")}"
    }

    suspend fun execute(context: Context, invocation: ToolInvocation): String = withContext(Dispatchers.IO) {
        val rootUri = ProjectWorkspace.uri(context) ?: throw IllegalStateException("Önce çalışma alanı bölümünden bir proje klasörü seçin.")
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: throw IllegalStateException("Seçilen proje klasörüne erişilemiyor. Klasörü yeniden seçin.")
        if (!root.isDirectory) throw IllegalStateException("Seçilen öğe bir klasör değil.")
        when (invocation.name) {
            "read_file" -> read(context, root, invocation.arguments.getString("path"))
            "search_files" -> search(context, root, invocation.arguments.getString("query"), invocation.arguments.optString("path").takeIf { it.isNotBlank() })
            "write_file" -> write(context, root, invocation.arguments.getString("path"), invocation.arguments.getString("content"))
            "edit_file" -> edit(context, root, invocation.arguments.getString("path"), invocation.arguments.getString("old_text"), invocation.arguments.getString("new_text"))
            "delete_file" -> delete(context, root, invocation.arguments.getString("path"))
            else -> throw IllegalArgumentException("Desteklenmeyen araç: ${invocation.name}")
        }
    }

    private fun read(context: Context, root: DocumentFile, raw: String): String {
        val file = resolve(context, root, raw, createParents = false) ?: throw IllegalArgumentException("Dosya bulunamadı: $raw")
        if (!file.isFile) throw IllegalArgumentException("Yalnızca dosya okunabilir.")
        if (isSensitive(file.name.orEmpty())) throw IllegalArgumentException("Gizli/kimlik bilgisi dosyaları araçla okunamaz.")
        if (!isTextFile(file)) throw IllegalArgumentException("Bu araç yalnızca tanınan metin dosyalarını okuyabilir.")
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { readBounded(it, 256 * 1024) } ?: throw IllegalStateException("Dosya açılamadı.")
        if (bytes.size > 256 * 1024) throw IllegalArgumentException("Dosya 256 KB sınırını aşıyor.")
        return String(bytes, Charsets.UTF_8).take(16_000)
    }

    private fun search(context: Context, root: DocumentFile, query: String, subpath: String?): String {
        require(query.isNotBlank()) { "Arama metni boş olamaz." }
        val start = if (subpath.isNullOrBlank()) root else resolve(context, root, subpath, createParents = false) ?: throw IllegalArgumentException("Klasör bulunamadı.")
        if (!start.isDirectory) throw IllegalArgumentException("Arama yolu klasör değil.")
        val results = mutableListOf<String>()
        var visited = 0
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 12 || visited >= 2_000 || results.size >= 100) return
            dir.listFiles().forEach { child ->
                if (visited >= 2_000 || results.size >= 100) return
                visited++
                if (child.name == ".git" || child.name?.startsWith(".") == true) return@forEach
                if (child.isDirectory) walk(child, depth + 1)
                else if (child.isFile && !isSensitive(child.name.orEmpty()) && isTextFile(child)) {
                    val text = runCatching { context.contentResolver.openInputStream(child.uri)?.use { readBounded(it, 128 * 1024) }?.let { if (it.size <= 128 * 1024) String(it, Charsets.UTF_8) else null } }.getOrNull()
                    text?.lines()?.forEachIndexed { line, value -> if (value.contains(query, ignoreCase = true) && results.size < 100) results.add("${child.name}:${line + 1}: ${value.take(240)}") }
                }
            }
        }
        walk(start, 0)
        return if (results.isEmpty()) "Eşleşme bulunamadı (en çok 2.000 dosya/klasör tarandı)." else results.joinToString("\n").take(16_000)
    }

    private fun write(context: Context, root: DocumentFile, path: String, content: String): String {
        require(content.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Dosya içeriği 256 KB sınırını aşıyor." }
        val file = resolve(context, root, path, createParents = true, createFile = true) ?: throw IllegalStateException("Dosya oluşturulamadı.")
        if (!file.isFile) throw IllegalArgumentException("Yazma aracı yalnızca dosyalarda çalışır.")
        if (!isTextFile(file)) throw IllegalArgumentException("Var olan ikili dosyaların üzerine yazılamaz.")
        val existing = context.contentResolver.openInputStream(file.uri)?.use { readBounded(it, 256 * 1024) }
            ?: throw IllegalStateException("Var olan dosya okunamadı.")
        if (existing.size > 256 * 1024) throw IllegalArgumentException("Var olan dosya 256 KB sınırını aşıyor.")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) } ?: throw IllegalStateException("Dosyaya yazılamadı.")
        return "Dosya kaydedildi: $path"
    }

    private fun edit(context: Context, root: DocumentFile, path: String, oldText: String, newText: String): String {
        require(oldText.isNotEmpty()) { "Değiştirilecek metin boş olamaz." }
        val file = resolve(context, root, path, createParents = false) ?: throw IllegalArgumentException("Dosya bulunamadı.")
        if (isSensitive(file.name.orEmpty())) throw IllegalArgumentException("Gizli/kimlik bilgisi dosyaları araçla düzenlenemez.")
        if (!isTextFile(file)) throw IllegalArgumentException("Bu araç yalnızca tanınan metin dosyalarını düzenleyebilir.")
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { readBounded(it, 256 * 1024) } ?: throw IllegalStateException("Dosya okunamadı.")
        if (bytes.size > 256 * 1024) throw IllegalArgumentException("Düzenlenecek dosya 256 KB sınırını aşıyor.")
        val current = String(bytes, Charsets.UTF_8)
        if (oldText !in current) throw IllegalArgumentException("Dosyada değiştirilecek metin bulunamadı. Dosyayı yeniden okuyup tam eşleşme gönderin.")
        val updated = current.replaceFirst(oldText, newText)
        if (updated.toByteArray(Charsets.UTF_8).size > 256 * 1024) throw IllegalArgumentException("Düzenleme 256 KB sınırını aşıyor.")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(updated) } ?: throw IllegalStateException("Dosya yazılamadı.")
        return "Dosya güncellendi: $path"
    }

    private fun delete(context: Context, root: DocumentFile, path: String): String {
        val file = resolve(context, root, path, createParents = false) ?: throw IllegalArgumentException("Dosya bulunamadı.")
        if (!file.isFile) throw IllegalArgumentException("Klasör silme aracı desteklenmiyor.")
        if (!file.delete()) throw IllegalStateException("Dosya silinemedi.")
        return "Dosya silindi: $path"
    }

    private fun resolve(context: Context, root: DocumentFile, raw: String, createParents: Boolean, createFile: Boolean = false): DocumentFile? {
        val path = raw.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains(':')) { "Proje köküne göreli bir dosya yolu girin." }
        val parts = path.split('/')
        require(parts.size <= 20 && parts.all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c.isISOControl() } }) { "Güvenli olmayan dosya yolu." }
        require(parts.none { it == ".git" || it.startsWith(".bende-") }) { "Git iç dosyaları bu araçla değiştirilemez." }
        require(parts.none { isSensitive(it) }) { "Gizli/anahtar dosyalarına erişim kapalı." }
        var current = root
        parts.forEachIndexed { index, part ->
            var child = current.findFile(part)
            val last = index == parts.lastIndex
            if (child == null && createParents && !last) child = current.createDirectory(part)
            if (child == null && createParents && last && createFile) child = current.createFile("text/plain", part)
            if (child == null) return null
            if (!last && !child.isDirectory) throw IllegalArgumentException("Yolun ara öğesi klasör değil: $part")
            current = child
        }
        return current
    }

    private fun isSensitive(name: String): Boolean {
        val n = name.lowercase()
        return n == ".env" || n.startsWith(".env.") || n.endsWith(".jks") || n.endsWith(".keystore") || n.contains("credential") || n.contains("secret") || n.contains("id_rsa") || n.contains("id_ed25519")
    }

    private fun isTextFile(file: DocumentFile): Boolean = file.type?.startsWith("text/") == true || file.name?.substringAfterLast('.', "")?.lowercase() in setOf("kt", "java", "gradle", "kts", "md", "txt", "xml", "json", "yaml", "yml", "toml", "py", "js", "ts", "html", "css", "sh", "properties", "sql", "c", "h", "cpp", "rs", "go")

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
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
