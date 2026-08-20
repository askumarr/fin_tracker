package com.fintracker.app.domain.statement

import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import java.util.Locale

/**
 * Shared helpers for bank statement narrations (Canara CSV / e-Passbook PDF and similar).
 *
 * Example UPI:
 * UPI/DR/740187328962/SHUBHAM/SBIN/xxx@ybl/Payment
 */
object StatementNarrationParser {

    data class ParsedNarration(
        val merchant: String?,
        val reference: String?,
        val paymentMode: PaymentMode,
        val typeHint: TransactionType?
    )

    fun parse(description: String): ParsedNarration {
        val text = description.replace(Regex("\\s+"), " ").trim()
        return ParsedNarration(
            merchant = extractMerchant(text),
            reference = extractReference(text),
            paymentMode = detectPaymentMode(text),
            typeHint = detectTypeHint(text)
        )
    }

    private fun detectTypeHint(text: String): TransactionType? {
        if (isCardBill(text)) return TransactionType.TRANSFER
        val isCredit = CREDIT_HINT.containsMatchIn(text)
        val isDebit = DEBIT_HINT.containsMatchIn(text)
        return when {
            isCredit && !isDebit -> TransactionType.INCOME
            isDebit -> TransactionType.EXPENSE
            else -> null
        }
    }

    fun detectPaymentMode(text: String): PaymentMode {
        val lower = text.lowercase(Locale.US)
        return when {
            "upi" in lower -> PaymentMode.UPI
            "imps" in lower -> PaymentMode.NET_BANKING
            "neft" in lower || "rtgs" in lower -> PaymentMode.NET_BANKING
            "nach" in lower || "ecs" in lower -> PaymentMode.NET_BANKING
            "atm" in lower || "pos" in lower -> PaymentMode.DEBIT_CARD
            else -> PaymentMode.UNKNOWN
        }
    }

    fun isCardBill(text: String): Boolean =
        CARD_BILL.containsMatchIn(text) || CRED_TOKEN.containsMatchIn(text)

    fun extractReference(text: String): String? {
        UPI_REF.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        // The UTR of an RTGS/NEFT leg, which the SMS alert for the same credit also quotes.
        WIRE_TRANSFER.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        IB_WIRE_TRANSFER.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        GENERIC_REF.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        TWELVE_DIGIT.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    fun extractMerchant(text: String): String? {
        UPI_MERCHANT.find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.length >= 2 }
            ?.let { return cleanMerchant(it) }

        // "RTGS CR-HDFCR52026072888542568-HDFC0000240-TMF REDEMPTION POOL A/C--//TATA MF-14555913"
        // The counterparty follows the IFSC; a PDF line-wrap can add spaces around the hyphens.
        WIRE_TRANSFER.find(text)?.groupValues?.getOrNull(3)
            ?.let { trimWireName(it) }
            ?.let { return cleanMerchant(it) }

        // "IB NEFT DR CNRBH00148610576 PANKAJ KUMAR SBIN0001216 33832240330 LAND PURCHASE"
        IB_WIRE_TRANSFER.find(text)?.groupValues?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.length >= 3 }
            ?.let { return cleanMerchant(it) }

        MANDATE_MERCHANT.find(text)?.groupValues?.getOrNull(1)
            ?.let { dropIdentifierTokens(it) }
            ?.takeIf { it.length >= 3 }
            ?.let { return cleanMerchant(it) }

        return null
    }

    /** Keeps the counterparty name and drops the trailing purpose fields Canara appends. */
    private fun trimWireName(raw: String): String? =
        raw.split(WIRE_NAME_END).firstOrNull()
            ?.trim()
            ?.trimEnd('-', '/', ',')
            ?.trim()
            ?.takeIf { it.length >= 3 }

    /** NACH/ECS rows append mandate ids ("GROWWINVESTTECHPR KILYEQT35RIBSM CNRB702..."). */
    private fun dropIdentifierTokens(raw: String): String =
        raw.trim()
            .split(' ')
            .takeWhile { token -> token.none { it.isDigit() } }
            .joinToString(" ")
            .trim()

    private fun cleanMerchant(raw: String): String =
        raw.replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('-', ',', '.', '/')
            .take(40)
            .ifBlank { raw.take(40) }

    private val CREDIT_HINT =
        Regex("(?i)(?:UPI\\s*/\\s*CR|UPI/CR|/CR/|\\bCR[- ]|credited|deposit)")
    private val DEBIT_HINT =
        Regex("(?i)(?:UPI\\s*/\\s*DR|UPI/DR|/DR/|\\bDR[- ]|debited|withdrawal|ecs|nach)")
    private val CARD_BILL = Regex(
        "(?i)(?:credit\\s*card\\s*(?:bill|pay)|cc\\s*(?:bill|payment)|card\\s*bill|" +
            "sbicard|bbps|cred\\s*club)"
    )
    private val CRED_TOKEN = Regex("(?i)\\bCRED\\b")
    private val UPI_REF = Regex("(?i)UPI/(?:DR|CR)/([0-9]{6,})")
    private val GENERIC_REF = Regex("(?i)(?:Ref|UTR|Chq)[:\\s]*([0-9A-Z]{6,})")
    private val TWELVE_DIGIT = Regex("(?i)\\b([0-9]{12})\\b")
    private val UPI_MERCHANT = Regex("(?i)UPI/(?:DR|CR)/[0-9]+/([^/]+)/")

    /** "<NEFT|RTGS> <Cr|Dr>-<UTR>-<IFSC>-<counterparty>", tolerating wrapped whitespace. */
    private val WIRE_TRANSFER = Regex(
        "(?i)\\b(?:NEFT|RTGS)\\s*(?:Cr|Dr)?\\s*-\\s*([A-Z]{4}[A-Z0-9]{8,})\\s*-\\s*" +
            "([A-Z]{4}0[A-Z0-9]{6})\\s*-\\s*(.{3,80})"
    )

    /** "IB NEFT DR <UTR> <counterparty> <IFSC> <account> <purpose>" */
    private val IB_WIRE_TRANSFER = Regex(
        "(?i)\\bIB\\s+(?:NEFT|RTGS)\\s+(?:DR|CR)\\s+([A-Z]{4}[A-Z0-9]{8,})\\s+" +
            "(.{3,60}?)\\s+[A-Z]{4}0[A-Z0-9]{6}\\b"
    )

    /** Name ends at "--", or at a hyphen followed by space or slash. "2025-26" stays intact. */
    private val WIRE_NAME_END = Regex("--|-(?=[\\s/])")
    private val MANDATE_MERCHANT =
        Regex("(?i)\\b(?:NACH|ECS(?:\\s+MANDATE)?)\\s+([A-Za-z][A-Za-z0-9&. ]{2,60})")
}
