package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.vitazonebussinessautomation.data.AppRepository

@Composable
expect fun ExcelImportTabScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
)

@Composable
expect fun BankDailyTabScreen(
    modifier: Modifier = Modifier
)

@Composable
expect fun FromLochinTabScreen(
    modifier: Modifier = Modifier
)
