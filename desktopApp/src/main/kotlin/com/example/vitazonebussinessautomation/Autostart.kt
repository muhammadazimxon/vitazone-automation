package com.example.vitazonebussinessautomation

import java.io.File

/**
 * Автозапуск при входе в Windows.
 *
 * Раньше запись делалась в реестр HKCU\...\Run строкой «"путь" --background». Java при вызове
 * reg.exe ломала в ней кавычки, в реестр попадало нерабочее значение: в списке автозагрузки
 * пункт был и показывался включённым, а Windows ничего не запускала. Теперь кладём ярлык в папку
 * «Автозагрузка» — его видно в shell:startup, можно проверить и поправить руками.
 *
 * Состояние (вкл/выкл) хранится отдельно от ярлыка, и при каждом запуске ярлык восстанавливается,
 * если его удалили или путь к программе изменился после переустановки.
 */
object Autostart {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val link: File? by lazy {
        System.getenv("APPDATA")?.let {
            File(it, "Microsoft\\Windows\\Start Menu\\Programs\\Startup\\Vitazone.lnk")
        }
    }
    private val prefFile = File(System.getProperty("user.home"), ".vitazone_automation/autostart.txt")

    /** Путь к .exe установленной версии; null — приложение запущено не из установщика. */
    private fun exePath(): String? = System.getProperty("jpackage.app-path")

    private fun run(vararg cmd: String): Int =
        runCatching {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
                .also { it.inputStream.readBytes() }.waitFor()
        }.getOrDefault(-1)

    private fun wanted(): Boolean = runCatching { prefFile.readText().trim() != "off" }.getOrDefault(true)

    private fun remember(on: Boolean) {
        runCatching {
            prefFile.parentFile.mkdirs()
            prefFile.writeText(if (on) "on" else "off")
        }
    }

    fun isEnabled(): Boolean = isWindows && wanted() && link?.exists() == true

    /** Ярлык создаёт PowerShell: путь передаётся отдельным свойством, кавычки сломать нечему. */
    private fun createLink(exe: String): Boolean {
        val lnk = link ?: return false
        runCatching { lnk.parentFile.mkdirs() }
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val script = "\$ws = New-Object -ComObject WScript.Shell; " +
            "\$sc = \$ws.CreateShortcut(${q(lnk.absolutePath)}); " +
            "\$sc.TargetPath = ${q(exe)}; " +
            "\$sc.Arguments = '--background'; " +
            "\$sc.WorkingDirectory = ${q(File(exe).parent ?: "")}; " +
            "\$sc.Save()"
        run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
        return lnk.exists()
    }

    fun set(enabled: Boolean) {
        if (!isWindows) return
        remember(enabled)
        if (enabled) exePath()?.let { createLink(it) } else runCatching { link?.delete() }
    }

    /**
     * Вызывается при каждом запуске: восстанавливает ярлык, если он пропал,
     * и убирает старую нерабочую запись в реестре. Возвращает итоговое состояние.
     */
    fun ensure(): Boolean {
        if (!isWindows) return false
        run("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "VitazoneSync", "/f")
        val exe = exePath() ?: return false
        if (!wanted()) {
            runCatching { link?.delete() }
            return false
        }
        createLink(exe)
        return link?.exists() == true
    }
}
