package com.example.vitazonebussinessautomation

import java.io.File

/** Автозапуск при входе в Windows через HKCU\...\Run. Работает только в установленной (jpackage) версии. */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "VitazoneSync"
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun exePath(): String? = System.getProperty("jpackage.app-path")

    private fun run(vararg cmd: String): Int =
        runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() }
            .getOrDefault(-1)

    fun isEnabled(): Boolean = isWindows && run("reg", "query", KEY, "/v", NAME) == 0

    fun set(enabled: Boolean) {
        if (!isWindows) return
        if (enabled) {
            val exe = exePath() ?: return
            run("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exe\"", "/f")
        } else {
            run("reg", "delete", KEY, "/v", NAME, "/f")
        }
    }

    /** При первом запуске установленного приложения включает автозапуск; дальше — выбор пользователя в трее. */
    fun enableOnFirstRun(): Boolean {
        if (!isWindows || exePath() == null) return false
        val marker = File(System.getProperty("user.home"), ".vitazone_automation/autostart_initialized")
        if (!marker.exists()) {
            set(true)
            runCatching { marker.parentFile.mkdirs(); marker.writeText("1") }
        }
        return isEnabled()
    }
}
