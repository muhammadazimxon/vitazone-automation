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
import com.example.vitazonebussinessautomation.domain.models.Payment
import com.example.vitazonebussinessautomation.domain.models.PaymentStatus
import com.example.vitazonebussinessautomation.ui.util.FormatUtils
import com.example.vitazonebussinessautomation.ui.util.PaymentStatusBadge
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.abs

@Composable
fun PaymentScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
) {
    val payments by repository.payments.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var paymentToEdit by remember { mutableStateOf<Payment?>(null) }

    val filteredPayments = remember(payments, searchQuery) {
        payments.filter { p ->
            searchQuery.isBlank() ||
                    p.inn.contains(searchQuery, ignoreCase = true) ||
                    p.companyName.contains(searchQuery, ignoreCase = true) ||
                    p.docNumber.contains(searchQuery, ignoreCase = true) ||
                    p.variant.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                placeholder = { Text("Поиск по ИНН, наименованию, № док., варианту...") },
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
                    paymentToEdit = null
                    showDialog = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новое поступление")
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
                Text("Дата", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("№ Док.", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("ИНН / Контрагент", modifier = Modifier.weight(2.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Сумма поступления", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Вариант оплаты", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Статус", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Действия", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Divider()

        if (filteredPayments.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (payments.isEmpty()) "Нет данных о поступлениях" else "Ничего не найдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredPayments, key = { it.id }) { item ->
                    Surface(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(FormatUtils.formatDate(item.date), modifier = Modifier.weight(1f), fontSize = 13.sp)

                            Text(item.docNumber.ifBlank { "—" }, modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Medium, fontSize = 13.sp)

                            Column(modifier = Modifier.weight(2.5f)) {
                                Text(item.companyName.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("ИНН: ${item.inn.ifBlank { "не указан" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Text(
                                FormatUtils.formatCurrency(item.amount),
                                modifier = Modifier.weight(1.5f),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )

                            Text(item.variant.ifBlank { "—" }, modifier = Modifier.weight(1.2f), fontSize = 13.sp)

                            Box(modifier = Modifier.weight(1f)) {
                                PaymentStatusBadge(item.status)
                            }

                            Row(
                                modifier = Modifier.width(90.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = {
                                        paymentToEdit = item
                                        showDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { repository.deletePayment(item.id) },
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
        PaymentDialog(
            payment = paymentToEdit,
            repository = repository,
            onDismiss = { showDialog = false },
            onSave = { saved ->
                repository.addOrUpdatePayment(saved)
                showDialog = false
            }
        )
    }
}

@Composable
private fun PaymentDialog(
    payment: Payment?,
    repository: AppRepository,
    onDismiss: () -> Unit,
    onSave: (Payment) -> Unit
) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate(2024, 10, 25) }

    var docNumber by remember { mutableStateOf(payment?.docNumber ?: "") }
    var inn by remember { mutableStateOf(payment?.inn ?: "") }
    var companyName by remember { mutableStateOf(payment?.companyName ?: "") }
    var dateStr by remember { mutableStateOf(payment?.date?.let { FormatUtils.formatDate(it) } ?: FormatUtils.formatDate(today)) }
    var amountStr by remember { mutableStateOf(payment?.let { abs(it.amount).toString() } ?: "") }
    var variant by remember { mutableStateOf(payment?.variant ?: "Перечисление") }
    var status by remember { mutableStateOf(payment?.status ?: PaymentStatus.PAID) }

    var isSearchingCompany by remember { mutableStateOf(false) }

    LaunchedEffect(inn) {
        if (inn.replace(Regex("\\D"), "").length >= 9 && companyName.isBlank()) {
            val localName = repository.lookupCompanyName(inn)
            if (localName != null) companyName = localName
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (payment == null) "Новое поступление" else "Редактирование поступления") },
        text = {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = docNumber,
                    onValueChange = { docNumber = it },
                    label = { Text("№ Документа / Платежного поручения") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inn,
                        onValueChange = { inn = it },
                        label = { Text("ИНН плательщика") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isSearchingCompany = true
                                val found = repository.orgInfoLookupService.lookupCompanyByInn(inn)
                                isSearchingCompany = false
                                if (found != null) companyName = found
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
                    label = { Text("Наименование плательщика") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("Сумма оплаты (сум)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = { dateStr = it },
                        label = { Text("Дата (ДД.ММ.ГГГГ)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = variant,
                    onValueChange = { variant = it },
                    label = { Text("Вариант оплаты (Перечисление/Терминал/...)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Статус оплаты:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = status == PaymentStatus.PAID,
                        onClick = { status = PaymentStatus.PAID },
                        label = { Text("Оплачено") }
                    )
                    FilterChip(
                        selected = status == PaymentStatus.AWAITING,
                        onClick = { status = PaymentStatus.AWAITING },
                        label = { Text("Ожидает") }
                    )
                    FilterChip(
                        selected = status == PaymentStatus.CANCELLED,
                        onClick = { status = PaymentStatus.CANCELLED },
                        label = { Text("Отменено") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedAmount = amountStr.toDoubleOrNull() ?: 0.0
                    val parsedDate = try {
                        val parts = dateStr.split(".")
                        LocalDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                    } catch (e: Exception) {
                        today
                    }

                    val candidate = Payment(
                        id = payment?.id ?: "pay-${System.currentTimeMillis()}",
                        docNumber = docNumber.trim(),
                        date = parsedDate,
                        inn = inn.trim(),
                        companyName = companyName.trim(),
                        amount = parsedAmount,
                        status = status,
                        variant = variant.trim()
                    )
                    onSave(candidate)
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
