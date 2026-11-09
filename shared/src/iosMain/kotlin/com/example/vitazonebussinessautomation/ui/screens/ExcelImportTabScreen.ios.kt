package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.vitazonebussinessautomation.data.AppRepository

@Composable
actual fun ExcelImportTabScreen(
    repository: AppRepository,
    modifier: Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Импорт Excel поддерживается на Desktop (macOS / Windows)")
    }
}

@Composable
actual fun BankDailyTabScreen(
    modifier: Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Банковская выписка поддерживается на Desktop (macOS / Windows)")
    }
}

@Composable
actual fun FromLochinTabScreen(
    modifier: Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Секция От лочин поддерживается на Desktop (macOS / Windows)")
    }
}
