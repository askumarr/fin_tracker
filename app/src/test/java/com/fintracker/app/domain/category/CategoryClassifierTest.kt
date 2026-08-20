package com.fintracker.app.domain.category

import com.fintracker.app.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryClassifierTest {

    @Test
    fun normalizesMerchantKey() {
        assertThat(CategoryClassifier.normalizeKey("AMAZON PAY IN E."))
            .isEqualTo("amazon pay in e")
        assertThat(CategoryClassifier.normalizeKey("@UPI_XXX yyy zzz"))
            .isEqualTo("upi xxx yyy zzz")
    }

    @Test
    fun merchantRootSkipsFillers() {
        assertThat(CategoryClassifier.merchantRoot("AMAZON PAY IN E")).isEqualTo("amazon")
        assertThat(CategoryClassifier.merchantRoot("@UPI_SWIGGY store")).isEqualTo("swiggy")
    }

    @Test
    fun classifiesAmazonCardSpendAsShopping() {
        val s = CategoryClassifier.suggestCategoryName(
            merchant = "AMAZON PAY IN E",
            rawText = "INR 304.00 spent using ICICI Bank Card XX3007 on 18-Jun-26 on AMAZON PAY IN E."
        )
        assertThat(s).isNotNull()
        assertThat(s!!.categoryName).isEqualTo("Shopping")
    }

    @Test
    fun classifiesSwiggyAsFoodAndBlinkitAsGroceries() {
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "SWIGGY")!!.categoryName
        ).isEqualTo("Food")
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "Blinkit")!!.categoryName
        ).isEqualTo("Groceries")
    }

    @Test
    fun classifiesUberAndIrctcAsTravel() {
        assertThat(CategoryClassifier.suggestCategoryName(merchant = "UBER")!!.categoryName)
            .isEqualTo("Travel")
        assertThat(CategoryClassifier.suggestCategoryName(merchant = "IRCTC")!!.categoryName)
            .isEqualTo("Travel")
    }

    @Test
    fun classifiesNetflixAsEntertainment() {
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "NETFLIX.COM")!!.categoryName
        ).isEqualTo("Entertainment")
    }

    @Test
    fun transferTypeMapsToTransfers() {
        val s = CategoryClassifier.suggestCategoryName(
            merchant = "SBI Card",
            type = TransactionType.TRANSFER
        )
        assertThat(s!!.categoryName).isEqualTo("Transfers")
    }

    @Test
    fun salaryCreditMapsToSalary() {
        val s = CategoryClassifier.suggestCategoryName(
            merchant = "ACME PVT LTD",
            rawText = "INR 85000.00 credited towards SALARY for Jul",
            type = TransactionType.INCOME
        )
        assertThat(s!!.categoryName).isEqualTo("Salary")
    }

    @Test
    fun creditedDoesNotMatchCredKeyword() {
        val s = CategoryClassifier.suggestCategoryName(
            merchant = null,
            rawText = "An amount of INR 1000 has been credited to XXXX6371"
        )
        assertThat(s?.categoryName).isNotEqualTo("Transfers")
    }

    @Test
    fun longerKeywordWinsOverShorter() {
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "Swiggy Instamart")!!.categoryName
        ).isEqualTo("Groceries")
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "Amazon Prime")!!.categoryName
        ).isEqualTo("Entertainment")
    }
}
