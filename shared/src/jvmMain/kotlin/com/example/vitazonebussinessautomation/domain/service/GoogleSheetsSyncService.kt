package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.domain.models.Shipment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class GoogleSheetBatchPayload(
    val shipments: List<GoogleSheetShipmentRow>,
    val payments: List<GoogleSheetPaymentRow> = emptyList(),
    val importRows: List<List<String>> = emptyList()
)

@Serializable
data class GoogleSheetShipmentRow(
    val date: String,
    val inn: String,
    val companyName: String,
    val docNumber: String,
    val amount: Double,
    val manager: String,
    val variant: String,
    val status: String
)

@Serializable
data class GoogleSheetPaymentRow(
    val docNumber: String,
    val date: String,
    val inn: String,
    val companyName: String,
    val amount: Double,
    val status: String,
    val note: String,
    val variant: String
)

@Serializable
data class SyncResponse(
    val success: Boolean,
    val added: Int? = null,
    val addedPayments: Int? = null,
    val error: String? = null
)

class GoogleSheetsSyncService {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendBatchToGoogleSheets(
        webAppUrl: String,
        importResult: ExcelImportResult,
        importRows: List<List<String>> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        val urlStr = webAppUrl.trim()
        if (urlStr.isBlank()) {
            return@withContext Result.failure(Exception("Укажите Web App URL вашего скрипта в Google Таблице"))
        }

        try {
            val shipmentRows = importResult.shipments.map { sh ->
                GoogleSheetShipmentRow(
                    date = "${sh.date.dayOfMonth.toString().padStart(2, '0')}.${sh.date.monthNumber.toString().padStart(2, '0')}.${sh.date.year}",
                    inn = sh.inn,
                    companyName = sh.companyName,
                    docNumber = sh.docNumber,
                    amount = sh.amount,
                    manager = sh.manager,
                    variant = sh.variant,
                    status = sh.status.displayText
                )
            }

            val paymentRows = importResult.payments.map { p ->
                GoogleSheetPaymentRow(
                    docNumber = p.docNumber,
                    date = "${p.date.dayOfMonth.toString().padStart(2, '0')}.${p.date.monthNumber.toString().padStart(2, '0')}.${p.date.year}",
                    inn = p.inn,
                    companyName = p.companyName,
                    amount = p.amount,
                    status = p.status.displayText,
                    note = p.companyName,
                    variant = p.variant
                )
            }

            val payload = GoogleSheetBatchPayload(shipments = shipmentRows, payments = paymentRows, importRows = importRows)
            val bodyJson = json.encodeToString(payload)

            val initialUrl = URL(urlStr)
            val conn = initialUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
            conn.connectTimeout = 180000
            conn.readTimeout = 180000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(bodyJson)
            }

            val responseCode = conn.responseCode
            val responseText: String

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER) {

                val location = conn.getHeaderField("Location")
                conn.disconnect()

                if (location.isNullOrBlank()) {
                    return@withContext Result.success("Отгрузок: ${shipmentRows.size}, Поступлений: ${paymentRows.size}")
                }

                // Follow redirect as GET request
                val redirectUrl = URL(location)
                val redirectConn = redirectUrl.openConnection() as HttpURLConnection
                redirectConn.requestMethod = "GET"
                redirectConn.connectTimeout = 180000
                redirectConn.readTimeout = 180000

                responseText = try {
                    redirectConn.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                } finally {
                    redirectConn.disconnect()
                }
            } else if (responseCode in 200..299) {
                responseText = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                return@withContext Result.failure(Exception("HTTP Error $responseCode: $errText"))
            }

            if (responseText.isBlank()) {
                return@withContext Result.success("Отгрузок: ${shipmentRows.size}, Поступлений: ${paymentRows.size}")
            }

            if (responseText.trim().startsWith("<!DOCTYPE") || responseText.trim().startsWith("<html")) {
                return@withContext Result.failure(
                    Exception("Google блокирует запрос. Убедитесь, что в деплое Apps Script стоит 'Кто имеет доступ = Все (Anyone)'.")
                )
            }

            return@withContext try {
                val res = json.decodeFromString<SyncResponse>(responseText)
                if (res.success) {
                    val shipCount = res.added ?: shipmentRows.size
                    val payCount = res.addedPayments ?: paymentRows.size
                    Result.success("Отгрузок: $shipCount, Поступлений: $payCount")
                } else {
                    Result.failure(Exception(res.error ?: "Ошибка Google Sheets"))
                }
            } catch (e: Exception) {
                Result.success("Отгрузок: ${shipmentRows.size}, Поступлений: ${paymentRows.size}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}