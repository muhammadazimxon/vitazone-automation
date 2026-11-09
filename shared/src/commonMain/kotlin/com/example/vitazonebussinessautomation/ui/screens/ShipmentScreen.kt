package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.data.AppRepository
import com.example.vitazonebussinessautomation.domain.models.Shipment
import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus
import com.example.vitazonebussinessautomation.domain.service.DocumentValidator
import com.example.vitazonebussinessautomation.domain.service.ValidationResult
import com.example.vitazonebussinessautomation.ui.util.FormatUtils
import com.example.vitazonebussinessautomation.ui.util.StatusBadge
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.abs

@Composable
fun ShipmentScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
) {
    val shipments by repository.shipments.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<ShipmentStatus?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var shipmentToEdit by remember { mutableStateOf<Shipment?>(null) }

    val filteredShipments = remember(shipments, searchQuery, statusFilter) {
        shipments.filter { sh ->
            val matchesQuery = searchQuery.isBlank() ||
                    sh.inn.contains(searchQuery, ignoreCase = true) ||
                    sh.companyName.contains(searchQuery, ignoreCase = true) ||
                    sh.docNumber.contains(searchQuery, ignoreCase = true) ||
                    sh.manager.contains(searchQuery, ignoreCase = true) ||
                    sh.variant.contains(searchQuery, ignoreCase = true)

            val matchesStatus = statusFilter == null || sh.status == statusFilter
            matchesQuery && matchesStatus
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Toolbar & Filter Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по ИНН, контрагенту, № док., менеджеру...") },
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

                // Status Filter Chips
                FilterChip(
                    selected = statusFilter == null,
                    onClick = { statusFilter = null },
                    label = { Text("Все (${shipments.size})") }
                )
                FilterChip(
                    selected = statusFilter == ShipmentStatus.AWAITING_PAYMENT,
                    onClick = { statusFilter = if (statusFilter == ShipmentStatus.AWAITING_PAYMENT) null else ShipmentStatus.AWAITING_PAYMENT },
                    label = { Text("Ожидает") }
                )
                FilterChip(
                    selected = statusFilter == ShipmentStatus.PAID,
                    onClick = { statusFilter = if (statusFilter == ShipmentStatus.PAID) null else ShipmentStatus.PAID },
                    label = { Text("Оплачено") }
                )
                FilterChip(
                    selected = statusFilter == ShipmentStatus.RETURN,
                    onClick = { statusFilter = if (statusFilter == ShipmentStatus.RETURN) null else ShipmentStatus.RETURN },
                    label = { Text("Возврат") }
                )
            }

            Button(
                onClick = {
                    shipmentToEdit = null
                    showDialog = true
                },
                modifier = Modifier.padding(start = 16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новая отгрузка")
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
                Text("Дата / Срок", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("ИНН / Контрагент", modifier = Modifier.weight(2.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("№ Док.", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Сумма", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Вариант", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Менеджер", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Статус", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Действия", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Divider()

        // Table List
        if (filteredShipments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (shipments.isEmpty()) "Нет данных об отгрузках" else "Ничего не найдено по фильтрам",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredShipments, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (item.status == ShipmentStatus.RETURN) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.2f)) {
                                Text(FormatUtils.formatDate(item.date), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    "до ${FormatUtils.formatDate(item.dueDate)} (${item.delayDays} дн.)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(modifier = Modifier.weight(2.2f)) {
                                Text(item.companyName.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("ИНН: ${item.inn.ifBlank { "не указан" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Text(item.docNumber.ifBlank { "—" }, modifier = Modifier.weight(1f), fontSize = 13.sp)

                            Text(
                                FormatUtils.formatCurrency(item.amount),
                                modifier = Modifier.weight(1.3f),
                                fontWeight = FontWeight.Bold,
                                color = if (item.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            )

                            Text(item.variant.ifBlank { "—" }, modifier = Modifier.weight(1f), fontSize = 13.sp)

                            Text(item.manager.ifBlank { "—" }, modifier = Modifier.weight(1f), fontSize = 13.sp)

                            Box(modifier = Modifier.weight(1.2f)) {
                                StatusBadge(item.status)
                            }

                            Row(
                                modifier = Modifier.width(90.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        shipmentToEdit = item
                                        showDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { repository.deleteShipment(item.id) },
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
        ShipmentDialog(
            shipment = shipmentToEdit,
            repository = repository,
            onDismiss = { showDialog = false },
            onSave = { saved ->
                repository.addOrUpdateShipment(saved)
                showDialog = false
            }
        )
    }
}

@Composable
private fun ShipmentDialog(
    shipment: Shipment?,
    repository: AppRepository,
    onDismiss: () -> Unit,
    onSave: (Shipment) -> Unit
) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate(2024, 10, 25) }

    var inn by remember { mutableStateOf(shipment?.inn ?: "") }
    var companyName by remember { mutableStateOf(shipment?.companyName ?: "") }
    var docNumber by remember { mutableStateOf(shipment?.docNumber ?: "") }
    var dateStr by remember { mutableStateOf(shipment?.date?.let { FormatUtils.formatDate(it) } ?: FormatUtils.formatDate(today)) }
    var delayDaysStr by remember { mutableStateOf(shipment?.delayDays?.toString() ?: "15") }
    var amountStr by remember { mutableStateOf(shipment?.let { abs(it.amount).toString() } ?: "") }
    var status by remember { mutableStateOf(shipment?.status ?: ShipmentStatus.AWAITING_PAYMENT) }
    var variant by remember { mutableStateOf(shipment?.variant ?: "Перечисление") }
    var manager by remember { mutableStateOf(shipment?.manager ?: "Алексей") }
    var note by remember { mutableStateOf(shipment?.note ?: "") }

    var isSearchingCompany by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Auto lookup company name from repository when INN changes
    LaunchedEffect(inn) {
        if (inn.replace(Regex("\\D"), "").length >= 9 && companyName.isBlank()) {
            val localName = repository.lookupCompanyName(inn)
            if (localName != null) {
                companyName = localName
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shipment == null) "Создание отгрузки" else "Редактирование отгрузки") },
        text = {
            Column(
                modifier = Modifier
                    .width(480.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (validationError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = validationError!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp
                        )
                    }
                }

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
                                isSearchingCompany = true
                                val found = repository.orgInfoLookupService.lookupCompanyByInn(inn)
                                isSearchingCompany = false
                                if (found != null) {
                                    companyName = found
                                }
                            }
                        },
                        enabled = inn.replace(Regex("\\D"), "").length >= 9 && !isSearchingCompany
                    ) {
                        if (isSearchingCompany) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("orginfo.uz", fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = companyName,
                    onValueChange = { companyName = it },
                    label = { Text("Наименование контрагента") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = docNumber,
                        onValueChange = { docNumber = it },
                        label = { Text("№ Накладной / Документа") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("Сумма (сум)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("Дата (ДД.ММ.ГГГГ)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = delayDaysStr,
                        onValueChange = { delayDaysStr = it },
                        label = { Text("Отсрочка (дней)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = variant,
                        onValueChange = { variant = it },
                        label = { Text("Вариант оплаты") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = manager,
                        onValueChange = { manager = it },
                        label = { Text("Менеджер") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Status Selection
                Text("Статус отгрузки:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = status == ShipmentStatus.AWAITING_PAYMENT,
                        onClick = { status = ShipmentStatus.AWAITING_PAYMENT },
                        label = { Text("Ожидает") }
                    )
                    FilterChip(
                        selected = status == ShipmentStatus.RETURN,
                        onClick = { status = ShipmentStatus.RETURN },
                        label = { Text("Возврат") }
                    )
                    FilterChip(
                        selected = status == ShipmentStatus.CANCELLED,
                        onClick = { status = ShipmentStatus.CANCELLED },
                        label = { Text("Отменено") }
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Примечание") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedAmount = amountStr.toDoubleOrNull() ?: 0.0
                    val delay = delayDaysStr.toIntOrNull() ?: 0
                    val parsedDate = try {
                        val parts = dateStr.split(".")
                        LocalDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                    } catch (e: Exception) {
                        today
                    }

                    val rawAmount = if (status == ShipmentStatus.RETURN) -abs(parsedAmount) else abs(parsedAmount)

                    val candidate = Shipment(
                        id = shipment?.id ?: "sh-${System.currentTimeMillis()}",
                        date = parsedDate,
                        delayDays = delay,
                        inn = inn.trim(),
                        companyName = companyName.trim(),
                        docNumber = docNumber.trim(),
                        amount = rawAmount,
                        status = status,
                        note = note.trim(),
                        variant = variant.trim(),
                        manager = manager.trim()
                    )

                    val valRes = DocumentValidator().validateShipmentDoc(candidate, repository.shipments.value)
                    if (valRes is ValidationResult.Invalid) {
                        validationError = valRes.message
                    } else {
                        onSave(candidate)
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
