package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.vitazonebussinessautomation.data.AppRepository

@Composable
actual fun ExcelImportTabScreen(
    repository: AppRepository,
    modifier: Modifier
) {
    ExcelImportScreen(repository = repository, modifier = modifier)
}

@Composable
actual fun BankDailyTabScreen(
    modifier: Modifier
) {
    BankDailyScreen(modifier = modifier)
}

@Composable
actual fun FromLochinTabScreen(
    modifier: Modifier
) {
    FromLochinScreen(modifier = modifier)
}
