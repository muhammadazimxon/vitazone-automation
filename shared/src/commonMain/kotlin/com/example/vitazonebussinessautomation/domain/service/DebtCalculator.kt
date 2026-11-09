package com.example.vitazonebussinessautomation.domain.service

import com.example.vitazonebussinessautomation.domain.models.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DebtCalculationSummary(
    val totalEffectiveAmount: Double,
    val totalPaidAmount: Double,
    val totalRemainingDebt: Double,
    val totalOverdueDebt: Double,
    val activeDebtCount: Int
)

data class DebtCalculationResult(
    val updatedShipments: List<Shipment>,
    val debtRecords: List<DebtRecord>,
    val summary: DebtCalculationSummary
)

class DebtCalculator {

    private fun normalizeId(value: String?): String {
        return value?.replace(Regex("\\s+"), "")?.uppercase() ?: ""
    }

    private fun getInnPrefix(value: String?): String {
        val trimmed = value?.trim() ?: ""
        val firstPart = trimmed.split("-").firstOrNull() ?: ""
        return firstPart.replace(Regex("\\D"), "")
    }

    private fun formatNumber(num: Double): String {
        val intPart = num.toLong()
        val decPart = ((abs(num) - abs(intPart.toDouble())) * 100).toLong()
        val decStr = if (decPart < 10) "0$decPart" else "$decPart"
        val formattedInt = intPart.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$formattedInt,$decStr"
    }

    fun calculate(
        shipments: List<Shipment>,
        payments: List<Payment>
    ): DebtCalculationResult {
        val n = shipments.size
        if (n == 0) {
            return DebtCalculationResult(
                updatedShipments = emptyList(),
                debtRecords = emptyList(),
                summary = DebtCalculationSummary(0.0, 0.0, 0.0, 0.0, 0)
            )
        }

        val isRet = BooleanArray(n)
        val inc = BooleanArray(n)
        val docS = Array(n) { "" }
        val amtS = DoubleArray(n)
        val retSum = mutableMapOf<String, Double>()
        val origRow = mutableMapOf<String, Int>()

        for (z in 0 until n) {
            val sh = shipments[z]
            val isReturn = sh.status == ShipmentStatus.RETURN
            val isCancelled = sh.status == ShipmentStatus.CANCELLED
            docS[z] = sh.docNumber.trim()
            amtS[z] = abs(sh.amount)
            isRet[z] = isReturn
            inc[z] = !isReturn && !isCancelled && sh.inn.isNotBlank()

            if (docS[z].isNotEmpty()) {
                if (isReturn) {
                    retSum[docS[z]] = (retSum[docS[z]] ?: 0.0) + amtS[z]
                } else if (!isCancelled && !origRow.containsKey(docS[z])) {
                    origRow[docS[z]] = z
                }
            }
        }

        val id = Array(n) { "" }
        val vr = Array(n) { "" }
        val am = DoubleArray(n)
        val returned = BooleanArray(n)
        val exactDoc = mutableMapOf<String, Int>()
        val prefDoc = mutableMapOf<String, Int>()

        for (q in 0 until n) {
            val sh = shipments[q]
            returned[q] = false
            vr[q] = sh.variant.trim()
            id[q] = if (inc[q]) normalizeId(sh.inn) else ""
            am[q] = 0.0
            if (!inc[q]) continue

            val doc = docS[q]
            val ret = if (doc.isNotEmpty() && origRow[doc] == q) (retSum[doc] ?: 0.0) else 0.0
            returned[q] = ret > 0.0
            am[q] = max(0.0, amtS[q] - ret)

            if (id[q].isNotEmpty() && vr[q].isNotEmpty() && doc.isNotEmpty() && am[q] > 0.0) {
                val ek = "${id[q]}|${vr[q]}|$doc"
                val pk = "${getInnPrefix(sh.inn)}|${vr[q]}|$doc"
                if (!exactDoc.containsKey(ek)) exactDoc[ek] = q
                if (!prefDoc.containsKey(pk)) prefDoc[pk] = q
            }
        }

        val pool = mutableMapOf<String, Double>()
        val directSum = mutableMapOf<Int, Double>()

        for (p in payments) {
            if (p.status != PaymentStatus.PAID) continue
            val pVar = p.variant.trim()
            if (pVar.isEmpty()) continue

            val pId = normalizeId(p.inn)
            if (pId.isEmpty()) continue

            val sum = abs(p.amount)
            val pDoc = p.docNumber.trim()

            if (pDoc.isNotEmpty()) {
                var paymentRow = exactDoc["$pId|$pVar|$pDoc"]
                if (paymentRow == null) {
                    paymentRow = prefDoc["${getInnPrefix(p.inn)}|$pVar|$pDoc"]
                }
                if (paymentRow != null) {
                    directSum[paymentRow] = (directSum[paymentRow] ?: 0.0) + sum
                    continue
                }
            }

            val key = "$pId|$pVar"
            pool[key] = (pool[key] ?: 0.0) + sum
        }

        val directPaid = Array<Double?>(n) { null }

        for ((r, paidDirectVal) in directSum) {
            var paidDirect = paidDirectVal
            if (paidDirect > am[r]) {
                val bk = "${id[r]}|${vr[r]}"
                pool[bk] = (pool[bk] ?: 0.0) + (paidDirect - am[r])
                paidDirect = am[r]
            }
            directPaid[r] = paidDirect
        }

        val spent = mutableMapOf<String, Double>()
        val updatedShipmentsList = mutableListOf<Shipment>()
        val debtRecordsList = mutableListOf<DebtRecord>()

        for (d in 0 until n) {
            val sh = shipments[d]
            val amount = am[d]
            var paid = 0.0
            var newStatus = sh.status

            if (isRet[d]) {
                newStatus = ShipmentStatus.RETURN
            } else {
                if (directPaid[d] != null) {
                    paid = directPaid[d]!!
                } else if (id[d].isNotEmpty() && vr[d].isNotEmpty() && amount > 0.0) {
                    val key = "${id[d]}|${vr[d]}"
                    val rem = (pool[key] ?: 0.0) - (spent[key] ?: 0.0)

                    if (rem > 0.0) {
                        var hasMore = false
                        for (nx in d + 1 until n) {
                            if (directPaid[nx] != null) continue
                            if (id[nx] == id[d] && vr[nx] == vr[d] && am[nx] > 0.0) {
                                hasMore = true
                                break
                            }
                        }

                        if (hasMore) {
                            paid = min(rem, amount)
                            spent[key] = (spent[key] ?: 0.0) + paid
                        } else {
                            paid = rem
                            spent[key] = (spent[key] ?: 0.0) + amount
                        }
                    }
                }

                if (id[d].isNotEmpty() && sh.status != ShipmentStatus.CANCELLED) {
                    newStatus = when {
                        amount > 0.0 && paid >= amount - 0.001 -> ShipmentStatus.PAID
                        amount <= 0.0 && returned[d] -> ShipmentStatus.PAID
                        paid > 0.001 -> ShipmentStatus.PARTIALLY_PAID
                        else -> ShipmentStatus.AWAITING_PAYMENT
                    }
                }
            }

            val updatedShipment = sh.copy(status = newStatus)
            updatedShipmentsList.add(updatedShipment)

            if (inc[d]) {
                var note = sh.note
                if (returned[d]) {
                    val returnTotal = retSum[docS[d]] ?: 0.0
                    val rn = "Возврат ${formatNumber(returnTotal)} по док. №${docS[d]}"
                    note = if (note.isNotBlank()) "$note | $rn" else rn
                }

                val origAmt = amtS[d]
                val retAmt = if (docS[d].isNotEmpty() && origRow[docS[d]] == d) (retSum[docS[d]] ?: 0.0) else 0.0
                val effAmt = amount
                val remainingDebt = max(0.0, effAmt - paid)

                debtRecordsList.add(
                    DebtRecord(
                        shipmentId = sh.id,
                        date = sh.date,
                        delayDays = sh.delayDays,
                        dueDate = sh.dueDate,
                        inn = sh.inn,
                        companyName = sh.companyName,
                        docNumber = sh.docNumber,
                        originalAmount = origAmt,
                        returnAmount = retAmt,
                        effectiveAmount = effAmt,
                        paidAmount = paid,
                        debtAmount = remainingDebt,
                        status = newStatus,
                        note = note,
                        variant = sh.variant,
                        manager = sh.manager
                    )
                )
            }
        }

        var totalEffective = 0.0
        var totalPaid = 0.0
        var totalRemaining = 0.0
        var totalOverdue = 0.0
        var activeCount = 0

        for (rec in debtRecordsList) {
            totalEffective += rec.effectiveAmount
            totalPaid += rec.paidAmount
            totalRemaining += rec.debtAmount
            if (rec.isOverdue) {
                totalOverdue += rec.debtAmount
            }
            if (rec.debtAmount > 0.01) {
                activeCount++
            }
        }

        val summary = DebtCalculationSummary(
            totalEffectiveAmount = totalEffective,
            totalPaidAmount = totalPaid,
            totalRemainingDebt = totalRemaining,
            totalOverdueDebt = totalOverdue,
            activeDebtCount = activeCount
        )

        return DebtCalculationResult(
            updatedShipments = updatedShipmentsList,
            debtRecords = debtRecordsList,
            summary = summary
        )
    }
}
