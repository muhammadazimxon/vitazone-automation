package com.example.vitazonebussinessautomation.data

actual class FileStorage {
    private val memoryStore = mutableMapOf<String, String>()

    actual fun saveText(filename: String, content: String) {
        memoryStore[filename] = content
    }

    actual fun loadText(filename: String): String? {
        return memoryStore[filename]
    }
}
