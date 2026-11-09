package com.example.vitazonebussinessautomation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.vitazonebussinessautomation.domain.service.SyncWorkerHolder

private object TrayIconPainter : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF2E7D32))
    }
}

fun main() = application {
    // Создание воркера запускает фоновую слежку за 1С сразу при старте приложения
    remember { SyncWorkerHolder.worker }

    var visible by remember { mutableStateOf(true) }
    var autostart by remember { mutableStateOf(Autostart.enableOnFirstRun()) }

    Tray(
        icon = TrayIconPainter,
        tooltip = "Vitazone: синхронизация 1С → Google Sheets",
        onAction = { visible = true }
    ) {
        Item("Открыть", onClick = { visible = true })
        CheckboxItem(
            "Автозапуск вместе с Windows",
            checked = autostart,
            onCheckedChange = {
                Autostart.set(it)
                autostart = Autostart.isEnabled()
            }
        )
        Separator()
        Item("Выход", onClick = ::exitApplication)
    }

    Window(
        // Закрытие окна только прячет его в трей — синхронизация продолжает работать
        onCloseRequest = { visible = false },
        visible = visible,
        title = "VitazoneBussinessAutomation",
    ) {
        App()
    }
}
