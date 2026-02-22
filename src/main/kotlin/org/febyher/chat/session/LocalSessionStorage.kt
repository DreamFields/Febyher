package org.febyher.chat.session

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.febyher.settings.AIProvider
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 按项目存储的本地会话（Moonshot / DeepSeek / NVIDIA 等非 Aurod 模型）
 * 存储路径：项目根目录/.idea/febyher/chat_sessions.json
 */
@Service(Service.Level.PROJECT)
class LocalSessionStorage(private val project: Project) {

    private val logger = Logger.getInstance(LocalSessionStorage::class.java)
    private val gson = Gson()
    private val listType = object : TypeToken<List<LocalChatSession>>() {}.type

    private fun storageFile(): File? {
        val basePath = project.basePath ?: return null
        val dir = File(basePath, ".idea/febyher")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "chat_sessions.json")
    }

    fun listSessions(): List<LocalChatSession> {
        val file = storageFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText(StandardCharsets.UTF_8)
            gson.fromJson<List<LocalChatSession>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load local sessions: ${e.message}")
            emptyList()
        }
    }

    fun saveSession(session: LocalChatSession) {
        val file = storageFile() ?: return
        val list = listSessions().toMutableList()
        val index = list.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            list[index] = session
        } else {
            list.add(0, session)
        }
        session.updatedAt = System.currentTimeMillis()
        try {
            file.writeText(gson.toJson(list), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to save local session: ${e.message}", e)
        }
    }

    fun deleteSession(id: String) {
        val file = storageFile() ?: return
        val list = listSessions().filter { it.id != id }
        try {
            file.writeText(gson.toJson(list), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to delete local session: ${e.message}", e)
        }
    }

    fun getSession(id: String): LocalChatSession? = listSessions().find { it.id == id }

    companion object {
        fun getInstance(project: Project): LocalSessionStorage = project.service()
    }
}
