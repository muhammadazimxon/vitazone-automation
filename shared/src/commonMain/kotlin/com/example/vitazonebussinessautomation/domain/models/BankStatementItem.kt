package com.example.vitazonebussinessautomation.domain.models

import kotlinx.datetime.LocalDate

data class BankStatementItem(
    val id: String,
    val date: LocalDate,
    val docNumber: String,
    val mfo: String,
    val account: String,
    val companyName: String,
    val inn: String,
    val details: String,
    val debit: Double,
    val credit: Double,
    val variant: String = "Вариант 1"
)
