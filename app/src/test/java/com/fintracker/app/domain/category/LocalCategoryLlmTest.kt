package com.fintracker.app.domain.category

import com.fintracker.app.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalCategoryLlmTest {

    @Test
    fun chickenMarketIsFood() {
        val s = LocalCategoryLlm.suggest(
            merchant = "RAM REDDY CHICKEN MARKET",
            rawText = "Acct XXXX6371 Dr, INR 150.00 on 26/06/26 to RAM REDDY CHICKEN MARKET"
        )
        assertThat(s).isNotNull()
        assertThat(s!!.categoryName).isEqualTo("Food")
    }

    @Test
    fun petrolPumpIsFuel() {
        val s = LocalCategoryLlm.suggest(merchant = "New Pump 2")
        assertThat(s!!.categoryName).isEqualTo("Fuel")
    }

    @Test
    fun foodPlazaIsFood() {
        val s = LocalCategoryLlm.suggest(merchant = "Avenue Food Plaza Pvt Ltd")
        assertThat(s!!.categoryName).isEqualTo("Food")
    }

    @Test
    fun skipsPersonToPersonUpi() {
        assertThat(LocalCategoryLlm.suggest(merchant = "G RAHUL KUMA")).isNull()
        assertThat(LocalCategoryLlm.suggest(merchant = "SHIVAMASH")).isNull()
    }

    @Test
    fun tradersLeanGroceries() {
        val s = LocalCategoryLlm.suggest(merchant = "pavan traders M")
        assertThat(s!!.categoryName).isEqualTo("Groceries")
    }

    @Test
    fun medicalStoreIsHealth() {
        val s = LocalCategoryLlm.suggest(merchant = "Apollo Pharmacy Biharsharif")
        assertThat(s!!.categoryName).isEqualTo("Health")
    }

    @Test
    fun doesNotOverrideKeywordClassifierWhenPresent() {
        // Local scorer is the fallback; Amazon is the keyword classifier's job.
        assertThat(
            CategoryClassifier.suggestCategoryName(merchant = "AMAZON PAY IN E")!!.categoryName
        ).isEqualTo("Shopping")
    }

    @Test
    fun incomeWithoutSalaryCueIsSkipped() {
        assertThat(
            LocalCategoryLlm.suggest(
                merchant = "FIDELITY STOCK PLAN",
                type = TransactionType.INCOME
            )
        ).isNull()
    }
}
