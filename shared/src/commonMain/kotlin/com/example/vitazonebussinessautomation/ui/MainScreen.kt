package com.example.vitazonebussinessautomation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.data.AppRepository
import com.example.vitazonebussinessautomation.ui.screens.ExcelImportTabScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val repository = remember { AppRepository() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Vitazone — Фоновая Авто-Служба 1С Бухгалтерии",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            "Автоматическое слежение за 1С, синхронизация при изменениях и выгрузка дебиторки в Google Sheets",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ExcelImportTabScreen(repository = repository)
        }
    }
}
