package com.example.vitazonebussinessautomation.data

import com.example.vitazonebussinessautomation.domain.models.*
import com.example.vitazonebussinessautomation.domain.service.DebtCalculationResult
import com.example.vitazonebussinessautomation.domain.service.DebtCalculator
import com.example.vitazonebussinessautomation.domain.service.OrgInfoLookupService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppStateData(
    val companies: List<Company> = emptyList(),
    val shipments: List<Shipment> = emptyList(),
    val payments: List<Payment> = emptyList()
)

class AppRepository {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val debtCalculator = DebtCalculator()
    val orgInfoLookupService = OrgInfoLookupService()
    private val storage = FileStorage()
    private val dataFileName = "app_data.json"

    private val _companies = MutableStateFlow<List<Company>>(emptyList())
    val companies: StateFlow<List<Company>> = _companies.asStateFlow()

    private val _shipments = MutableStateFlow<List<Shipment>>(emptyList())
    val shipments: StateFlow<List<Shipment>> = _shipments.asStateFlow()

    private val _payments = MutableStateFlow<List<Payment>>(emptyList())
    val payments: StateFlow<List<Payment>> = _payments.asStateFlow()

    private val _debtResult = MutableStateFlow<DebtCalculationResult?>(null)
    val debtResult: StateFlow<DebtCalculationResult?> = _debtResult.asStateFlow()

    init {
        loadData()
    }

    fun recalculate() {
        val currentShipments = _shipments.value
        val currentPayments = _payments.value
        val res = debtCalculator.calculate(currentShipments, currentPayments)
        _debtResult.value = res

        if (res.updatedShipments != currentShipments) {
            _shipments.value = res.updatedShipments
        }
    }

    fun addOrUpdateCompany(company: Company) {
        val current = _companies.value.toMutableList()
        val index = current.indexOfFirst { it.normalizedInn == company.normalizedInn }
        if (index >= 0) {
            current[index] = company
        } else {
            current.add(company)
        }
        _companies.value = current
        saveData()
    }

    fun deleteCompany(inn: String) {
        val norm = inn.replace(Regex("\\D"), "")
        _companies.value = _companies.value.filter { it.normalizedInn != norm }
        saveData()
    }

    fun addOrUpdateShipment(shipment: Shipment) {
        val current = _shipments.value.toMutableList()
        val index = current.indexOfFirst { it.id == shipment.id }
        if (index >= 0) {
            current[index] = shipment
        } else {
            current.add(0, shipment)
        }
        _shipments.value = current

        if (shipment.inn.isNotBlank() && shipment.companyName.isNotBlank()) {
            addOrUpdateCompany(Company(inn = shipment.inn, name = shipment.companyName))
        }

        recalculate()
        saveData()
    }

    fun deleteShipment(id: String) {
        _shipments.value = _shipments.value.filter { it.id != id }
        recalculate()
        saveData()
    }

    fun addOrUpdatePayment(payment: Payment) {
        val current = _payments.value.toMutableList()
        val index = current.indexOfFirst { it.id == payment.id }
        if (index >= 0) {
            current[index] = payment
        } else {
            current.add(0, payment)
        }
        _payments.value = current

        if (payment.inn.isNotBlank() && payment.companyName.isNotBlank()) {
            addOrUpdateCompany(Company(inn = payment.inn, name = payment.companyName))
        }

        recalculate()
        saveData()
    }

    fun deletePayment(id: String) {
        _payments.value = _payments.value.filter { it.id != id }
        recalculate()
        saveData()
    }

    fun lookupCompanyName(innInput: String): String? {
        val norm = innInput.replace(Regex("\\D"), "")
        val local = _companies.value.firstOrNull { it.normalizedInn == norm }
        return local?.name
    }

    fun resetToSampleData() {
        val sample = createSampleData()
        _companies.value = sample.companies
        _shipments.value = sample.shipments
        _payments.value = sample.payments
        recalculate()
        saveData()
    }

    private fun saveData() {
        try {
            val state = AppStateData(
                companies = _companies.value,
                shipments = _shipments.value,
                payments = _payments.value
            )
            val jsonStr = json.encodeToString(state)
            storage.saveText(dataFileName, jsonStr)
        } catch (e: Exception) {
            println("Error saving data: ${e.message}")
        }
    }

    private fun loadData() {
        try {
            val jsonStr = storage.loadText(dataFileName)
            if (!jsonStr.isNullOrBlank()) {
                val state = json.decodeFromString<AppStateData>(jsonStr)
                if (state.shipments.isNotEmpty() || state.companies.isNotEmpty()) {
                    _companies.value = state.companies
                    _shipments.value = state.shipments
                    _payments.value = state.payments
                    recalculate()
                    return
                }
            }
        } catch (e: Exception) {
            println("Error loading data: ${e.message}")
        }

        resetToSampleData()
    }

    private fun createSampleData(): AppStateData {
        val today = LocalDate(2024, 10, 25)

        val companies = listOf(
            Company("303303592", "OOO \"VITAZONE PHARM\""),
            Company("313422013", "ИП \"AZIZOV MED LOGISTICS\""),
            Company("201620909", "OOO \"PHARMA GROUP SERVIS\""),
            Company("305112890", "ЧП \"MEDITECH SUPPLY\"")
        )

        val shipments = listOf(
            Shipment(
                id = "sh-1",
                date = LocalDate.parse("2024-10-01"),
                delayDays = 15,
                inn = "303303592",
                companyName = "OOO \"VITAZONE PHARM\"",
                docNumber = "АК-1001",
                amount = 15000000.0,
                status = ShipmentStatus.AWAITING_PAYMENT,
                note = "Отгрузка медикаментов партия №1",
                variant = "Перечисление",
                manager = "Алексей"
            ),
            Shipment(
                id = "sh-2",
                date = LocalDate.parse("2024-10-05"),
                delayDays = 10,
                inn = "313422013",
                companyName = "ИП \"AZIZOV MED LOGISTICS\"",
                docNumber = "АК-1002",
                amount = 8500000.0,
                status = ShipmentStatus.AWAITING_PAYMENT,
                note = "Расходные материалы",
                variant = "Терминал",
                manager = "Дмитрий"
            ),
            Shipment(
                id = "sh-3",
                date = LocalDate.parse("2024-10-10"),
                delayDays = 20,
                inn = "201620909",
                companyName = "OOO \"PHARMA GROUP SERVIS\"",
                docNumber = "АК-1003",
                amount = 22000000.0,
                status = ShipmentStatus.AWAITING_PAYMENT,
                note = "Оборудование и диагностика",
                variant = "Перечисление",
                manager = "Алексей"
            ),
            Shipment(
                id = "sh-4",
                date = LocalDate.parse("2024-10-12"),
                delayDays = 14,
                inn = "303303592",
                companyName = "OOO \"VITAZONE PHARM\"",
                docNumber = "АК-1004",
                amount = 5000000.0,
                status = ShipmentStatus.RETURN,
                note = "Частичный возврат по накладной АК-1001",
                variant = "Перечисление",
                manager = "Алексей"
            )
        )

        val payments = listOf(
            Payment(
                id = "pay-1",
                docNumber = "АК-1001",
                date = LocalDate.parse("2024-10-18"),
                inn = "303303592",
                companyName = "OOO \"VITAZONE PHARM\"",
                amount = 10000000.0,
                status = PaymentStatus.PAID,
                variant = "Перечисление"
            ),
            Payment(
                id = "pay-2",
                docNumber = "",
                date = LocalDate.parse("2024-10-12"),
                inn = "313422013",
                companyName = "ИП \"AZIZOV MED LOGISTICS\"",
                amount = 8500000.0,
                status = PaymentStatus.PAID,
                variant = "Терминал"
            )
        )

        return AppStateData(companies, shipments, payments)
    }
}
