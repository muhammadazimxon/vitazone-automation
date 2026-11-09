package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.data.AppRepository
import com.example.vitazonebussinessautomation.domain.models.Company
import kotlinx.coroutines.launch

@Composable
fun BaseScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val companies by repository.companies.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var companyToEdit by remember { mutableStateOf<Company?>(null) }

    // Live search testing state
    var liveInn by remember { mutableStateOf("") }
    var liveNameResult by remember { mutableStateOf<String?>(null) }
    var isSearchingLive by remember { mutableStateOf(false) }

    val filteredCompanies = remember(companies, searchQuery) {
        companies.filter {
            searchQuery.isBlank() ||
                    it.inn.contains(searchQuery, ignoreCase = true) ||
                    it.name.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Live OrgInfo Test Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Онлайн-проверка ИНН в Государственном реестре (orginfo.uz)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = liveInn,
                        onValueChange = {
                            liveInn = it
                            liveNameResult = null
                        },
                        placeholder = { Text("Введите ИНН (например, 303303592)...") },
                        modifier = Modifier.width(320.dp),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isSearchingLive = true
                                liveNameResult = repository.orgInfoLookupService.lookupCompanyByInn(liveInn)
                                isSearchingLive = false
                            }
                        },
                        enabled = liveInn.replace(Regex("\\D"), "").length >= 9 && !isSearchingLive
                    ) {
                        if (isSearchingLive) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Запрос...")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Проверить ИНН")
                        }
                    }

                    if (liveNameResult != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Найдено: $liveNameResult",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск в локальной базе по ИНН или названию...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                modifier = Modifier.width(360.dp),
                singleLine = true
            )

            Button(
                onClick = {
                    companyToEdit = null
                    showDialog = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить контрагента")
            }
        }

        // Table Header
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ИНН организации", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Наименование контрагента", modifier = Modifier.weight(4f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Действия", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Divider()

        if (filteredCompanies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (companies.isEmpty()) "База контрагентов пуста" else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredCompanies, key = { it.normalizedInn }) { item ->
                    Surface(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.inn, modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                            Text(item.name, modifier = Modifier.weight(4f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                            Row(
                                modifier = Modifier.width(90.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        companyToEdit = item
                                        showDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { repository.deleteCompany(item.inn) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    if (showDialog) {
        CompanyDialog(
            company = companyToEdit,
            repository = repository,
            onDismiss = { showDialog = false },
            onSave = { saved ->
                repository.addOrUpdateCompany(saved)
                showDialog = false
            }
        )
    }
}

@Composable
private fun CompanyDialog(
    company: Company?,
    repository: AppRepository,
    onDismiss: () -> Unit,
    onSave: (Company) -> Unit
) {
    val scope = rememberCoroutineScope()
    var inn by remember { mutableStateOf(company?.inn ?: "") }
    var name by remember { mutableStateOf(company?.name ?: "") }
    var isSearching by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (company == null) "Новый контрагент" else "Редактирование контрагента") },
        text = {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inn,
                        onValueChange = { inn = it },
                        label = { Text("ИНН организации") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isSearching = true
                                val found = repository.orgInfoLookupService.lookupCompanyByInn(inn)
                                isSearching = false
                                if (found != null) name = found
                            }
                        },
                        enabled = inn.replace(Regex("\\D"), "").length >= 9 && !isSearching
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("orginfo.uz", fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Наименование организации") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inn.isNotBlank() && name.isNotBlank()) {
                        onSave(Company(inn = inn.trim(), name = name.trim()))
                    }
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
