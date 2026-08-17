package com.fintracker.app.domain.sms

import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class SmsParseEngineTest {

    private lateinit var engine: SmsParseEngine

    @Before
    fun setup() {
        engine = SmsParseEngine { SmsTemplateLoader.builtInTemplates() }
    }

    @Test
    fun parsesHdfcUpiDebit() {
        val sms = SmsMessage(
            sender = "HDFCBK",
            body = "Rs.250.00 debited via UPI to coffee@oksbi UPI Ref 412345678901",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(25000L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.UPI)
        assertThat(parsed.merchant).contains("coffee")
        assertThat(parsed.reference).isEqualTo("412345678901")
    }

    @Test
    fun ignoresOtp() {
        val sms = SmsMessage(
            sender = "HDFCBK",
            body = "Your OTP is 123456. Do not share with anyone.",
            receivedAt = System.currentTimeMillis()
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun ignoresOtpQuotingTransactionAmount() {
        val sms = SmsMessage(
            sender = "VM-ICICIB",
            body = "851278 is One-Time Password for INR 860.00 transaction towards AMAZON using " +
                "ICICI Bank Credit Card XX3007. OTPs are SECRET. DO NOT disclose.",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun ignoresCreditCardEStatement() {
        val sms = SmsMessage(
            sender = "AD-IDFCFB",
            body = "The eStatement for your FIRST Select Credit Card XX9904 is here " +
                "https://idfcfir.st/x. Min Due: INR 116.82 Total Due: INR 116.82",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun ignoresStatementDueNotice() {
        val sms = SmsMessage(
            sender = "VA-ICICIB",
            body = "ICICI Bank Credit Card XX3007 Statement is sent to as****@gmail.com. " +
                "Total of Rs 24,256.55 or minimum of Rs 1,220.00 is due by 30-JUL-26.",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun ignoresMutualFundRedemption() {
        val sms = SmsMessage(
            sender = "AD-PPFAS",
            body = "Dear Investor, Your redemption transaction amounting to Rs.2,05,881.77 in " +
                "folio no. 18164545 has been processed.- PPFAS MF",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun ignoresNpsUnitAllotment() {
        val sms = SmsMessage(
            sender = "AD-Protean",
            body = "PRAN XX8409: Units for (JUL-2026) contribution of Rs.13,738.00 credited with " +
                "NAV of 27/07/26 -Protean",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun bankDebitForSipIsStillExpense() {
        val sms = SmsMessage(
            sender = "VM-HDFCBK",
            body = "Rs.5,000.00 debited from A/c XX1234 towards SIP purchase. Avl Bal Rs.20,000.00",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amountPaise).isEqualTo(500000L)
    }

    @Test
    fun yesBankPaymentReceivedIsTransfer() {
        val sms = SmsMessage(
            sender = "VM-YESBNK",
            body = "Dear Cardmember, payment of Rs.2,535.67 is received towards your YES BANK " +
                "Credit Card ending 2847. It will reflect in your Credit card within 1-2 working days",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(parsed.amountPaise).isEqualTo(253567L)
    }

    @Test
    fun credClubDebitIsTransfer() {
        val sms = SmsMessage(
            sender = "AD-CANBNK",
            body = "Dear Customer, Acct XXXX6371 Dr. INR 8,459.77 on 25/07/26 to CRED Club; " +
                "UPI: 657230203227; Bal INR 2,41,401.03. Not you? SMS BLOCKUPI-CanaraBank",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(parsed.amountPaise).isEqualTo(845977L)
    }

    @Test
    fun repeatedCardPaymentAlertsShareDedupeKey() {
        val cardSide = SmsMessage(
            sender = "TX-FEDSCP-S",
            body = "Yay! We've received your payment of ₹8,347.95 towards your Scapia Federal " +
                "credit card. -Federal Bank",
            receivedAt = 1_700_000_000_000L
        )
        val bankSide = SmsMessage(
            sender = "VM-SBIINB",
            body = "Rs.8,347.95 debited from A/c XX9224 towards Scapia credit card bill payment.",
            receivedAt = 1_700_000_000_000L + 4 * 60 * 1000L
        )
        val first = engine.parse(cardSide)
        val second = engine.parse(bankSide)
        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(second!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(first.dedupeKey).isEqualTo(second.dedupeKey)
    }

    @Test
    fun credClubAndCardPaymentShareDedupeKey() {
        val bank = SmsMessage(
            sender = "AD-CANBNK",
            body = "Dear Customer, Acct XXXX6371 Dr. INR 8,459.77 on 25/07/26 to CRED Club; " +
                "UPI: 657230203227; Bal INR 2,41,401.03.",
            receivedAt = 1_700_000_000_000L
        )
        val card = SmsMessage(
            sender = "VA-SBICRD-S",
            body = "We have received payment of Rs.8,459.77 via BBPS & the same has been credited " +
                "to your SBI Credit Card.",
            receivedAt = 1_700_000_000_000L + 60_000L
        )
        val a = engine.parse(bank)
        val b = engine.parse(card)
        assertThat(a).isNotNull()
        assertThat(b).isNotNull()
        assertThat(a!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(b!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(a.dedupeKey).isEqualTo(b.dedupeKey)
    }

    @Test
    fun twoRealMerchantsSameAmountKeepDifferentKeys() {
        val a = engine.parse(
            SmsMessage(
                sender = "VM-HDFCBK",
                body = "Rs.500.00 spent on your HDFC Bank Credit Card at Swiggy on 12-08-25.",
                receivedAt = 1_700_000_000_000L
            )
        )
        val b = engine.parse(
            SmsMessage(
                sender = "VM-ICICIB",
                body = "Rs.500.00 spent on your ICICI Bank Credit Card at Zomato on 12-08-25.",
                receivedAt = 1_700_000_000_000L + 120_000L
            )
        )
        assertThat(a).isNotNull()
        assertThat(b).isNotNull()
        assertThat(a!!.dedupeKey).isNotEqualTo(b!!.dedupeKey)
    }

    @Test
    fun dedupeKeyStableForSameRef() {
        val a = engine.buildDedupeKey(10000, 1000L, "999", "x", "HDFC")
        val b = engine.buildDedupeKey(10000, 999999L, "999", "y", "SBI")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun parsesFederalScapiaCardTxn() {
        val sms = SmsMessage(
            sender = "TX-FEDSCP-S",
            body = "Hi! Your txn of ₹35.00 at Avenue Food Plaza Pvt Ltd on your Scapia Federal " +
                "RuPay credit card was successful. Not you? Go to Scapia support on the app.- Federal Bank",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(3500L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.CREDIT_CARD)
        assertThat(parsed.merchant).isEqualTo("Avenue Food Plaza Pvt Ltd")
        assertThat(parsed.needsReview).isFalse()
    }

    @Test
    fun parsesFederalScapiaRewardsVariant() {
        val sms = SmsMessage(
            sender = "TX-FEDSCP-S",
            body = "Your txn of ₹2,159.67 at DMart on your Scapia Federal RuPay credit card " +
                "earned you 5% rewards! Not you? Call 18002961199. - Federal Bank",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(215967L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.merchant).isEqualTo("DMart")
    }

    @Test
    fun parsesHdfcSentFormat() {
        val sms = SmsMessage(
            sender = "VM-HDFCBK",
            body = "Sent Rs.150.00\nFrom HDFC Bank A/C x1234\nTo VASANTHA STORES\nOn 12/08/25\n" +
                "Ref 523456789012\nNot You? Call 18002586161",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(15000L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.merchant).isEqualTo("VASANTHA STORES")
        assertThat(parsed.reference).isEqualTo("523456789012")
    }

    @Test
    fun parsesPaymentSuccessFormat() {
        val sms = SmsMessage(
            sender = "AX-PHONEPE",
            body = "Payment of Rs.320.00 to Blinkit is successful. UPI Ref 412398765432.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(32000L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.merchant).isEqualTo("Blinkit")
    }

    @Test
    fun creditCardMentionIsNotTreatedAsIncome() {
        val sms = SmsMessage(
            sender = "AD-SBIINB",
            body = "Your SBI credit card was used for a txn of INR 899.00 at Swiggy on 12-08-25.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amountPaise).isEqualTo(89900L)
    }

    @Test
    fun cardBillPaymentIsTransferNotIncome() {
        val sms = SmsMessage(
            sender = "VA-SBICRD-S",
            body = "We have received payment of Rs.1,284.00 via BBPS & the same has been credited " +
                "to your SBI Credit Card. Your available limit is Rs.212,000.03.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(parsed.amountPaise).isEqualTo(128400L)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.NET_BANKING)
    }

    @Test
    fun bankSideCardBillDebitIsTransfer() {
        val sms = SmsMessage(
            sender = "VM-SBIINB",
            body = "Rs.1,284.00 debited from A/c XX9224 towards SBI Credit Card payment on 12-08-25.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.TRANSFER)
        assertThat(parsed.amountPaise).isEqualTo(128400L)
    }

    @Test
    fun cardPurchaseIsStillExpense() {
        val sms = SmsMessage(
            sender = "VA-SBICRD-S",
            body = "Rs.3,520.00 spent on your SBI Credit Card ending 9224 at MyntraDesignsPvtLtd " +
                "on 09/02/26. Trxn. not done by you? Report at https://sbicard.com/Dispute",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.amountPaise).isEqualTo(352000L)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.CREDIT_CARD)
        assertThat(parsed.merchant).isEqualTo("MyntraDesignsPvtLtd")
    }

    @Test
    fun cardBillDueReminderIsNotATransaction() {
        val sms = SmsMessage(
            sender = "VA-SBICRD-S",
            body = "Your SBI Credit Card bill of Rs.5,000.00 is due on 20-08-25. " +
                "Minimum amount due Rs.250.00.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed?.type).isNotEqualTo(TransactionType.TRANSFER)
    }

    @Test
    fun refundToCardIsNotATransfer() {
        val sms = SmsMessage(
            sender = "VA-SBICRD-S",
            body = "Refund of Rs.499.00 has been credited to your SBI Credit Card ending 9224.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.INCOME)
    }

    @Test
    fun cardRefundWithRevisedDueIsIncome() {
        val sms = SmsMessage(
            sender = "VA-ICICIB",
            body = "AMAZON PAY IN IRCTC refund of Rs 1,960.00 credited to ICICI Bank Credit Card " +
                "XX3007 on 15-AUG-26. Revised total due Rs 0, minimum due Rs .00",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.INCOME)
        assertThat(parsed.amountPaise).isEqualTo(196000L)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.CREDIT_CARD)
        assertThat(parsed.merchant).isEqualTo("AMAZON PAY IN IRCTC")
    }

    @Test
    fun balanceCrSuffixIsNotTreatedAsIncome() {
        val sms = SmsMessage(
            sender = "VM-BANKX",
            body = "Rs.200.00 debited for card purchase. Avl Bal Rs.5,000.00 Cr",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.EXPENSE)
    }

    @Test
    fun genuineCreditIsStillIncome() {
        val sms = SmsMessage(
            sender = "VM-BANKX",
            body = "Your a/c XX1234 is credited with INR 25,000.00 on 01-08-25 by SALARY AUG.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.INCOME)
        assertThat(parsed.amountPaise).isEqualTo(2_500_000L)
    }

    @Test
    fun ignoresPromotionalOffer() {
        val sms = SmsMessage(
            sender = "VM-BANKX",
            body = "You are pre-approved for a personal loan of Rs.5,00,000 at 10.5%. Apply now! T&C apply.",
            receivedAt = 1_700_000_000_000L
        )
        assertThat(engine.parse(sms)).isNull()
    }

    @Test
    fun parsesGenericNetBanking() {
        val sms = SmsMessage(
            sender = "BANK",
            body = "INR 1,500.00 debited from your account via NEFT. Avl Bal INR 10,000.00",
            receivedAt = System.currentTimeMillis()
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.paymentMode).isEqualTo(PaymentMode.NET_BANKING)
        assertThat(parsed.amountPaise).isEqualTo(150000L)
    }

    @Test
    fun capturesClosingBalanceFromCanaraAlert() {
        val sms = SmsMessage(
            sender = "CANBNK",
            body = "An amount of INR 10,000.00 has been DEBITED to your account XXXX6371 on " +
                "12/08/2026. Total Avail.bal INR 17,88,623.39.. Dial 1930 to report cyber fraud- " +
                "Canara Bank",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(1_000_000L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.balanceAfterPaise).isEqualTo(178_862_339L)
    }

    @Test
    fun parsesRtgsCreditEvenWhenSenderNameMentionsRedemption() {
        val sms = SmsMessage(
            sender = "CANBNK",
            body = "An amount of INR 30,85,687.08 has been credited to XXXX6371 on 28/07/2026 " +
                "towards RTGS by Sender TMF REDEMPTION POOL A/C, IFSC HDFC0000240, Sender A/c " +
                "XXXX9201, HDFC BANK, MUMBAI  SANDOZ HOUS, UTR HDFCR52026072888542568, " +
                "Total Avail. Bal INR 4783179.35- Canara Bank",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.type).isEqualTo(TransactionType.INCOME)
        assertThat(parsed.amountPaise).isEqualTo(308_568_708L)
        assertThat(parsed.balanceAfterPaise).isEqualTo(478_317_935L)
        assertThat(parsed.maskedAccount).isEqualTo("XXXX6371")
    }

    @Test
    fun keepsPaisePrecisionOnLargeAmounts() {
        val sms = SmsMessage(
            sender = "CANBNK",
            body = "An amount of INR 30,85,687.08 has been DEBITED to your account XXXX6371. " +
                "Total Avail.bal INR 47,83,179.35",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(308_568_708L)
        assertThat(parsed.balanceAfterPaise).isEqualTo(478_317_935L)
    }

    @Test
    fun extractsMerchantAfterSecondOnInIciciCardAlert() {
        val sms = SmsMessage(
            sender = "ICICIB",
            body = "INR 304.00 spent using ICICI Bank Card XX3007 on 18-Jun-26 on " +
                "AMAZON PAY IN E. Avl Limit: INR 91,696.00. If not you, call 1800 2662/" +
                "SMS BLOCK 3007 to 9215676766.",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(30_400L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.CREDIT_CARD)
        assertThat(parsed.merchant).isEqualTo("AMAZON PAY IN E")
        assertThat(parsed.maskedAccount).isEqualTo("XX3007")
    }

    @Test
    fun extractsMerchantAfterAtSignInYesBankCardAlert() {
        val sms = SmsMessage(
            sender = "YESBNK",
            body = "INR 10.00 spent on YES BANK Card X2847 @UPI_XXX yyy zzz 15-06-2026",
            receivedAt = 1_700_000_000_000L
        )
        val parsed = engine.parse(sms)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.amountPaise).isEqualTo(1_000L)
        assertThat(parsed.type).isEqualTo(TransactionType.EXPENSE)
        assertThat(parsed.paymentMode).isEqualTo(PaymentMode.UPI)
        assertThat(parsed.merchant).isEqualTo("UPI_XXX yyy zzz")
        assertThat(parsed.maskedAccount).isEqualTo("X2847")
    }

    @Test
    fun sameAmountDayDifferentBalanceGivesDistinctDedupeKeys() {
        fun canara(balance: String) = SmsMessage(
            sender = "CANBNK",
            body = "An amount of INR 10,000.00 has been DEBITED to your account XXXX6371 on " +
                "12/08/2026. Total Avail.bal INR $balance.. Dial 1930 to report cyber fraud- " +
                "Canara Bank",
            receivedAt = 1_700_000_000_000L
        )
        val a = engine.parse(canara("17,88,623.39"))!!
        val b = engine.parse(canara("17,98,623.39"))!!
        val c = engine.parse(canara("17,78,623.39"))!!
        assertThat(setOf(a.dedupeKey, b.dedupeKey, c.dedupeKey)).hasSize(3)
    }
}
