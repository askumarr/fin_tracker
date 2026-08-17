package com.fintracker.app.domain.sms

import android.content.Context
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsTemplateLoader @Inject constructor(
    private val context: Context
) {
    private val gson = Gson()

    @Volatile
    private var cached: List<SmsTemplate>? = null

    fun load(): List<SmsTemplate> {
        cached?.let { return it }
        val templates = builtInTemplates().toMutableList()
        try {
            context.assets.open("sms_templates/banks.json").bufferedReader().use { reader ->
                val type = object : TypeToken<List<JsonTemplate>>() {}.type
                val jsonTemplates: List<JsonTemplate> = gson.fromJson(reader, type)
                templates += jsonTemplates.map { it.toDomain() }
            }
        } catch (_: Exception) {
            // Assets optional; built-ins always available.
        }
        cached = templates
        return templates
    }

    companion object {
        fun builtInTemplates(): List<SmsTemplate> = listOf(
            SmsTemplate(
                id = "hdfc_upi_debit",
                bankHint = "HDFC",
                senderPatterns = listOf("HDFCBK", "HDFC"),
                bodyPatterns = listOf(
                    """(?i)Rs\.?\s*([0-9,]+\.?[0-9]*)\s+debited.*?(?:UPI|VPA).*?(?:to|at)\s+([A-Za-z0-9@._\-]+).*?(?:Ref|UPI Ref)[^\d]*(\d+)"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.92f
            ),
            SmsTemplate(
                id = "hdfc_card_debit",
                bankHint = "HDFC",
                senderPatterns = listOf("HDFCBK", "HDFC"),
                bodyPatterns = listOf(
                    """(?i)Rs\.?\s*([0-9,]+\.?[0-9]*)\s+(?:spent|debited).*?(?:on|using)\s+(?:HDFC\s+)?(?:Bank\s+)?(?:Debit|Credit)\s+Card.*?at\s+([A-Za-z0-9 *&._\-]+)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.DEBIT_CARD,
                confidence = 0.88f
            ),
            SmsTemplate(
                id = "sbi_upi_debit",
                bankHint = "SBI",
                senderPatterns = listOf("SBIINB", "SBI", "CBSSBI"),
                bodyPatterns = listOf(
                    """(?i)(?:A/C|A/c).*?(?:debited|Dr).*?INR\s*([0-9,]+\.?[0-9]*).*?(?:to|towards)\s+([A-Za-z0-9@._\-]+).*?(?:UPI|Ref)[^\d]*(\d+)"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.9f
            ),
            SmsTemplate(
                id = "sbi_credit",
                bankHint = "SBI",
                senderPatterns = listOf("SBIINB", "SBI", "CBSSBI"),
                bodyPatterns = listOf(
                    """(?i)\b(?:credited|Cr)\b.*?INR\s*([0-9,]+\.?[0-9]*).*?(?:from|by)\s+([A-Za-z0-9@._\- ]+)"""
                ),
                merchantGroup = 2,
                type = TransactionType.INCOME,
                paymentMode = PaymentMode.UPI,
                confidence = 0.85f
            ),
            SmsTemplate(
                id = "icici_upi_debit",
                bankHint = "ICICI",
                senderPatterns = listOf("ICICIB", "ICICI"),
                bodyPatterns = listOf(
                    """(?i)INR\s*([0-9,]+\.?[0-9]*)\s+spent.*?(?:UPI|on).*?(?:to|at)\s+([A-Za-z0-9@._\-]+).*?(?:UPI:|Ref)[^\d]*(\d+)"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.91f
            ),
            SmsTemplate(
                id = "axis_upi_debit",
                bankHint = "AXIS",
                senderPatterns = listOf("AXISBK", "AX-AXIS", "AXIS"),
                bodyPatterns = listOf(
                    """(?i)INR\s*([0-9,]+\.?[0-9]*)\s+debited.*?(?:UPI).*?(?:to|VPA)\s+([A-Za-z0-9@._\-]+).*?(?:UPI Ref|Ref)[^\d]*(\d+)"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.9f
            ),
            SmsTemplate(
                id = "kotak_upi_debit",
                bankHint = "KOTAK",
                senderPatterns = listOf("KOTAKB", "KOTAK"),
                bodyPatterns = listOf(
                    """(?i)Rs\.?\s*([0-9,]+\.?[0-9]*)\s+debited.*?(?:UPI).*?(?:to|at)\s+([A-Za-z0-9@._\-]+).*?(?:Ref)[^\d]*(\d+)"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.9f
            ),
            SmsTemplate(
                id = "hdfc_upi_sent",
                bankHint = "HDFC",
                senderPatterns = listOf("HDFCBK", "HDFC"),
                bodyPatterns = listOf(
                    """(?is)Sent\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s*From\s.*?\bTo\s+(.+?)\s*On\s.*?\bRef\D*(\d{6,})""",
                    """(?is)Sent\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s*From\s.*?\bTo\s+(.+?)\s*On\s"""
                ),
                merchantGroup = 2,
                referenceGroup = 3,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.92f
            ),
            SmsTemplate(
                id = "federal_card_txn",
                bankHint = "FEDERAL",
                senderPatterns = listOf("FEDSCP", "FEDBNK", "FEDERAL", "SCAPIA"),
                bodyPatterns = listOf(
                    """(?i)txn\s+of\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+at\s+(.+?)\s+on\s+your\b""",
                    """(?i)txn\s+of\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+at\s+(.+?)\s+(?:was|is)\s+(?:successful|completed|declined)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.CREDIT_CARD,
                confidence = 0.92f
            ),
            SmsTemplate(
                id = "generic_card_txn_on_or_at_merchant",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    // ICICI: "... Card XX3007 on 18-Jun-26 on AMAZON PAY IN E. Avl Limit ..."
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+spent\s+using\s+.*?\bCard\s+[Xx*]*\d{4}\s+on\s+\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\d{2,4}\s+on\s+(.+?)(?=\.\s*(?:Avl|Available)\b|[.!]|$)""",
                    // YES: "... Card X2847 @UPI_XXX yyy zzz 15-06-2026"
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+spent\s+on\s+.*?\bCard\s+[Xx*]*\d{4}\s+@\s*(.+?)\s+\d{1,2}[-/]\d{1,2}[-/]\d{2,4}(?:\b|$)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.CREDIT_CARD,
                confidence = 0.86f
            ),
            SmsTemplate(
                id = "generic_card_txn_at_merchant",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)(?:txn|transaction|purchase)\s+(?:of|for)\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+at\s+(.+?)\s+(?:on\s+your\b|was\b|is\b|using\b)""",
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:spent|charged|paid|debited)\s+(?:on|using|from|via).*?\bat\s+(.+?)(?:\s+on\s|\.|,|$)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UNKNOWN,
                confidence = 0.78f
            ),
            SmsTemplate(
                id = "generic_refund_with_merchant",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)^\s*(.{2,60}?)\s+refund\s+of\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:has\s+been\s+|is\s+)?credited"""
                ),
                amountGroup = 2,
                merchantGroup = 1,
                type = TransactionType.INCOME,
                paymentMode = PaymentMode.UNKNOWN,
                confidence = 0.86f
            ),
            SmsTemplate(
                id = "generic_refund_credit",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)\brefund(?:ed)?\s+(?:of\s*)?(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:has\s+been\s+|is\s+|was\s+)?(?:credited|processed|received)""",
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:has\s+been\s+|is\s+)?(?:refunded|credited\s+as\s+refund)"""
                ),
                type = TransactionType.INCOME,
                paymentMode = PaymentMode.UNKNOWN,
                confidence = 0.8f
            ),
            SmsTemplate(
                id = "generic_payment_success",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)(?:payment|payment of|paid)\s*(?:of)?\s*(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:to|at)\s+(.+?)\s+(?:is\s+|was\s+|has\s+been\s+)?(?:successful|completed|received|made)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UNKNOWN,
                confidence = 0.76f
            ),
            SmsTemplate(
                id = "generic_upi_debit",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:debited|spent|paid).*?\bUPI\b.*?([A-Za-z0-9._\-]+@[A-Za-z0-9]+)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.UPI,
                confidence = 0.7f
            ),
            SmsTemplate(
                id = "generic_netbanking_debit",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:debited|Dr).*?\b(?:net\s*banking|NEFT|IMPS|RTGS)\b"""
                ),
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.NET_BANKING,
                confidence = 0.72f
            ),
            SmsTemplate(
                id = "generic_credit_card",
                bankHint = null,
                senderPatterns = listOf(".*"),
                bodyPatterns = listOf(
                    """(?i)(?:Rs\.?|INR|₹)\s*([0-9,]+\.?[0-9]*)\s+(?:spent|charged).*?\bcredit\s*card\b.*?at\s+([A-Za-z0-9 *&._\-]+)"""
                ),
                merchantGroup = 2,
                type = TransactionType.EXPENSE,
                paymentMode = PaymentMode.CREDIT_CARD,
                confidence = 0.75f
            )
        )
    }

    private data class JsonTemplate(
        val id: String,
        val bankHint: String?,
        val senderPatterns: List<String>,
        val bodyPatterns: List<String>,
        val amountGroup: Int = 1,
        val merchantGroup: Int? = null,
        val referenceGroup: Int? = null,
        val balanceGroup: Int? = null,
        val accountGroup: Int? = null,
        val type: String,
        val paymentMode: String,
        val confidence: Float = 0.85f
    ) {
        fun toDomain() = SmsTemplate(
            id = id,
            bankHint = bankHint,
            senderPatterns = senderPatterns,
            bodyPatterns = bodyPatterns,
            amountGroup = amountGroup,
            merchantGroup = merchantGroup,
            referenceGroup = referenceGroup,
            balanceGroup = balanceGroup,
            accountGroup = accountGroup,
            type = TransactionType.valueOf(type),
            paymentMode = PaymentMode.valueOf(paymentMode),
            confidence = confidence
        )
    }
}
