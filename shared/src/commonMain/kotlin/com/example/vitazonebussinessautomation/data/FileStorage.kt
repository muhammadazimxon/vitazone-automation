package com.example.vitazonebussinessautomation.data

expect class FileStorage() {
    fun saveText(filename: String, content: String)
    fun loadText(filename: String): String?
}
