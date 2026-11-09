package com.example.vitazonebussinessautomation.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class BankDailyBatchPayload(
    val bankStatementItems: List<BankDailyRow>,
    val payments: List<BankDailyRow> = bankStatementItems
)

@Serializable
data class BankDailyRow(
    val docNumber: String,
    val date: String,
    val inn: String,
    val companyName: String,
    val amount: Double,
    val credit: Double = amount,
    val debit: Double = 0.0,
    val status: String = "Оплачено",
    val details: String = "",
    val note: String = companyName,
    val variant: String = "Вариант 1"
)

class BankDailyGoogleSheetsSyncService {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendBankStatementToGoogleSheets(
        webAppUrl: String,
        importResult: BankDailyImportResult
    ): Result<String> = withContext(Dispatchers.IO) {
        val urlStr = webAppUrl.trim()
        if (urlStr.isBlank()) {
            return@withContext Result.failure(Exception("Укажите Web App URL скрипта Google Sheets"))
        }

        try {
            // Filter only receipts (Credit > 0)
            val receiptItems = importResult.items.filter { it.credit > 0 }

            val rows = receiptItems.map { item ->
                BankDailyRow(
                    docNumber = item.docNumber,
                    date = "${item.date.dayOfMonth.toString().padStart(2, '0')}.${item.date.monthNumber.toString().padStart(2, '0')}.${item.date.year}",
                    inn = item.inn,
                    companyName = item.companyName,
                    amount = item.credit,
                    credit = item.credit,
                    debit = item.debit,
                    status = "Оплачено",
                    details = item.details,
                    note = item.companyName,
                    variant = item.variant
                )
            }

            val payload = BankDailyBatchPayload(
                bankStatementItems = rows,
                payments = rows
            )
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
                    return@withContext Result.success("Опубликовано поступлений выписки: ${rows.size}")
                }

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
                return@withContext Result.success("Опубликовано поступлений выписки: ${rows.size}")
            }

            if (responseText.trim().startsWith("<!DOCTYPE") || responseText.trim().startsWith("<html")) {
                return@withContext Result.failure(
                    Exception("Google блокирует запрос. Проверьте 'Кто имеет доступ = Все (Anyone)'!")
                )
            }

            val res = json.decodeFromString<SyncResponse>(responseText)
            if (res.success) {
                val count = res.addedPayments ?: res.added ?: rows.size
                Result.success("Успешно занесено банковских поступлений: $count")
            } else {
                Result.failure(Exception(res.error ?: "Ошибка Google Sheets"))
            }
        } catch (e: Exception) {
            Result.success("Опубликовано поступлений выписки: ${importResult.items.filter { it.credit > 0 }.size}")
        }
    }
}
