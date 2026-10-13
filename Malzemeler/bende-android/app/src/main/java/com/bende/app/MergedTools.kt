package com.bende.app

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Ajanın kullanabileceği tüm araç ailelerini tek şemada birleştirir ve doğru yürütücüye dağıtır:
 *
 * - [AgentTools]: seçili proje dosya araçları (yalnızca proje klasörü seçiliyse şemaya girer;
 *   yazma/düzenleme/silme uygulamanın ayrı onay penceresini bekler).
 * - [BuildTools]: GitHub/bulut derleme araçları (github_*, build_*; github_push_project,
 *   build_start ve build_download uygulamanın ayrı onay penceresini bekler).
 * - [BrowserTools]: tarayıcı devri araçları (browser_*; onayını kendi tarayıcı onay
 *   penceresiyle yürütür, bu yüzden ev sahibi onay diyaloğu açılmaz).
 */
object MergedTools {
    /** Bir araç çağrısının toplam yürütme süresi sınırı (onay bekleme dahil). */
    private const val EXEC_TIMEOUT_MS = 15 * 60_000L

    fun openAiDefinitions(includeFileTools: Boolean): JSONArray = JSONArray().apply {
        if (includeFileTools) appendAll(AgentTools.openAiDefinitions())
        appendAll(BuildTools.openAiDefinitions())
        appendAll(BrowserTools.openAiDefinitions())
    }

    fun anthropicDefinitions(includeFileTools: Boolean): JSONArray = JSONArray().apply {
        if (includeFileTools) appendAll(AgentTools.anthropicDefinitions())
        appendAll(BuildTools.anthropicDefinitions())
        appendAll(BrowserTools.anthropicDefinitions())
    }

    fun isBrowserTool(name: String): Boolean = name.startsWith("browser_")

    fun isBuildTool(name: String): Boolean = name.startsWith("github_") || name.startsWith("build_")

    fun display(invocation: ToolInvocation): String = when {
        isBrowserTool(invocation.name) -> BrowserTools.display(invocation)
        isBuildTool(invocation.name) -> BuildTools.display(invocation)
        else -> AgentTools.display(invocation)
    }

    /**
     * Aracı yürütür. Onay gerekiyorsa [requestApproval] ile kullanıcıya sorar; ret durumunda
     * işlem yapılmaz. Tarayıcı araçları kendi onay akışını yürüttüğü için bu geri çağrıyı
     * kullanmaz. Toplam süre [EXEC_TIMEOUT_MS] ile sınırlıdır.
     */
    suspend fun execute(
        context: Context,
        invocation: ToolInvocation,
        requestApproval: suspend (ToolInvocation) -> Boolean
    ): String {
        val result = withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            when {
                isBrowserTool(invocation.name) -> BrowserTools.execute(context, invocation)
                isBuildTool(invocation.name) -> {
                    if (BuildTools.needsApproval(invocation.name) && !requestApproval(invocation)) {
                        return@withTimeoutOrNull "Kullanıcı işlemi reddetti; işlem yapılmadı."
                    }
                    BuildTools.execute(context, invocation)
                }
                else -> {
                    if (AgentTools.needsApproval(invocation.name) && !requestApproval(invocation)) {
                        return@withTimeoutOrNull "Kullanıcı işlemi reddetti; dosya değiştirilmedi."
                    }
                    AgentTools.execute(context, invocation)
                }
            }
        }
        return result ?: "Araç 15 dakika içinde tamamlanamadı (onay veya yürütme zaman aşımı)."
    }

    private fun JSONArray.appendAll(other: JSONArray) {
        for (i in 0 until other.length()) put(other.get(i))
    }
}