package com.example.vitazonebussinessautomation.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppConfig(
    val webAppUrl: String = "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec",
    val monitoredPath: String = "",
    val onecHttpUrl: String = "",
    val autoStartOnLaunch: Boolean = true,
    val checkIntervalSeconds: Int = 10,
    val lastSyncTimestamp: Long = 0L
)

class AppConfigStorage {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val configFile: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val configDir = File(userHome, ".vitazone_automation")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        File(configDir, "config.json")
    }

    fun loadConfig(): AppConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                val config = json.decodeFromString<AppConfig>(content)
                if (config.webAppUrl.isBlank()) {
                    config.copy(webAppUrl = "https://script.google.com/macros/s/AKfycbyZL7c5orqIimJQYGX_vlZ5qHQFLfVyd8ex9twVkwaOsLayijlbVH9RvNLNeOkYxkDknw/exec")
                } else config
            } else {
                AppConfig()
            }
        } catch (e: Exception) {
            AppConfig()
        }
    }

    fun saveConfig(config: AppConfig) {
        try {
            val content = json.encodeToString(AppConfig.serializer(), config)
            configFile.writeText(content)
        } catch (e: Exception) {
            // Error ignored
        }
    }
}
