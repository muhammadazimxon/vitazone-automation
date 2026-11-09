package com.example.vitazonebussinessautomation.data

import java.io.File

actual class FileStorage {
    private val baseDir: File by lazy {
        val userHome = System.getProperty("user.home") ?: "."
        val dir = File(userHome, ".vitazone_automation")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    actual fun saveText(filename: String, content: String) {
        try {
            val file = File(baseDir, filename)
            file.writeText(content)
        } catch (e: Exception) {
            println("Error saving file $filename: ${e.message}")
        }
    }

    actual fun loadText(filename: String): String? {
        return try {
            val file = File(baseDir, filename)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            println("Error loading file $filename: ${e.message}")
            null
        }
    }
}
