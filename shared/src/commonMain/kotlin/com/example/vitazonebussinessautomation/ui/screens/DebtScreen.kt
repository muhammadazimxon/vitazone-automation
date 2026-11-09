package com.example.vitazonebussinessautomation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vitazonebussinessautomation.data.AppRepository
import com.example.vitazonebussinessautomation.domain.models.DebtRecord
import com.example.vitazonebussinessautomation.domain.models.ShipmentStatus
import com.example.vitazonebussinessautomation.ui.util.FormatUtils
import com.example.vitazonebussinessautomation.ui.util.StatusBadge

@Composable
fun DebtScreen(
    repository: AppRepository,
    modifier: Modifier = Modifier
) {
    val debtResultState by repository.debtResult.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var onlyOverdue by remember { mutableStateOf(false) }
    var selectedStatus by remember { mutableStateOf<ShipmentStatus?>(null) }

    val debtResult = debtResultState
    val allRecords = debtResult?.debtRecords ?: emptyList()
    val summary = debtResult?.summary

    val filteredRecords = remember(allRecords, searchQuery, onlyOverdue, selectedStatus) {
        allRecords.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                    item.inn.contains(searchQuery, ignoreCase = true) ||
                    item.companyName.contains(searchQuery, ignoreCase = true) ||
                    item.docNumber.contains(searchQuery, ignoreCase = true) ||
                    item.manager.contains(searchQuery, ignoreCase = true) ||
                    item.variant.contains(searchQuery, ignoreCase = true)

            val matchesOverdue = !onlyOverdue || item.isOverdue
            val matchesStatus = selectedStatus == null || item.status == selectedStatus

            matchesSearch && matchesOverdue && matchesStatus
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top KPI Cards Summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            KpiCard(
                title = "Общий объем отгрузок",
                value = FormatUtils.formatCurrency(summary?.totalEffectiveAmount ?: 0.0),
                subtitle = "С учетом возвратов",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )

            KpiCard(
                title = "Погашено (Оплачено)",
                value = FormatUtils.formatCurrency(summary?.totalPaidAmount ?: 0.0),
                subtitle = "По всем поступлениям",
                containerColor = Color(0xFFE8F5E9),
                contentColor = Color(0xFF1B5E20),
                modifier = Modifier.weight(1f)
            )

            KpiCard(
                title = "Текущая задолженность",
                value = FormatUtils.formatCurrency(summary?.totalRemainingDebt ?: 0.0),
                subtitle = "Остаток к оплате",
                containerColor = Color(0xFFFFF3E0),
                contentColor = Color(0xFFE65100),
                modifier = Modifier.weight(1f)
            )

            KpiCard(
                title = "Просроченный долг",
                value = FormatUtils.formatCurrency(summary?.totalOverdueDebt ?: 0.0),
                subtitle = "Срок оплаты истек",
                containerColor = Color(0xFFFFEBEE),
                contentColor = Color(0xFFB71C1C),
                modifier = Modifier.weight(1f)
            )
        }

        // Toolbar & Filters
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

                FilterChip(
                    selected = onlyOverdue,
                    onClick = { onlyOverdue = !onlyOverdue },
                    label = { Text("Только просроченные") }
                )

                FilterChip(
                    selected = selectedStatus == ShipmentStatus.AWAITING_PAYMENT,
                    onClick = {
                        selectedStatus = if (selectedStatus == ShipmentStatus.AWAITING_PAYMENT) null else ShipmentStatus.AWAITING_PAYMENT
                    },
                    label = { Text("Ожидает оплаты") }
                )

                FilterChip(
                    selected = selectedStatus == ShipmentStatus.PARTIALLY_PAID,
                    onClick = {
                        selectedStatus = if (selectedStatus == ShipmentStatus.PARTIALLY_PAID) null else ShipmentStatus.PARTIALLY_PAID
                    },
                    label = { Text("Частично оплачено") }
                )
            }

            Button(
                onClick = { repository.recalculate() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Пересчитать задолженность")
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
                Text("Сумма к оплате", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Оплачено", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Остаток долга", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Статус", modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Менеджер / Вариант", modifier = Modifier.weight(1.3f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Divider()

        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allRecords.isEmpty()) "Задолженностей не найдено" else "Ничего не найдено по фильтрам",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredRecords, key = { it.shipmentId }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (item.isOverdue) Color(0xFFFFF8F8) else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.2f)) {
                                Text(FormatUtils.formatDate(item.date), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    "до ${FormatUtils.formatDate(item.dueDate)}",
                                    fontSize = 11.sp,
                                    color = if (item.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (item.isOverdue) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            Column(modifier = Modifier.weight(2.2f)) {
                                Text(item.companyName.ifBlank { "—" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("ИНН: ${item.inn}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Text(item.docNumber.ifBlank { "—" }, modifier = Modifier.weight(1f), fontSize = 13.sp)

                            Column(modifier = Modifier.weight(1.3f)) {
                                Text(FormatUtils.formatCurrency(item.effectiveAmount), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (item.returnAmount > 0) {
                                    Text(
                                        "Возврат: -${FormatUtils.formatCurrency(item.returnAmount)}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }

                            Text(
                                FormatUtils.formatCurrency(item.paidAmount),
                                modifier = Modifier.weight(1.2f),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF2E7D32),
                                fontSize = 13.sp
                            )

                            Text(
                                FormatUtils.formatCurrency(item.debtAmount),
                                modifier = Modifier.weight(1.3f),
                                fontWeight = FontWeight.Bold,
                                color = if (item.debtAmount > 0.01) MaterialTheme.colorScheme.error else Color.Gray,
                                fontSize = 13.sp
                            )

                            Box(modifier = Modifier.weight(1.2f)) {
                                StatusBadge(item.status)
                            }

                            Column(modifier = Modifier.weight(1.3f)) {
                                Text(item.manager.ifBlank { "—" }, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                Text(item.variant.ifBlank { "—" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    subtitle: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(title, color = contentColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(value, color = contentColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = contentColor.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
