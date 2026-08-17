package com.fintracker.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.ReviewStatus
import com.fintracker.app.domain.model.TransactionSource
import com.fintracker.app.domain.model.TransactionType

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "other",
    val isArchived: Boolean = false,
    val isDefault: Boolean = false
)

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val bankHint: String? = null,
    val maskedNumber: String? = null,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("categoryId"),
        Index("accountId"),
        Index("occurredAt"),
        Index("dedupeKey", unique = true)
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountPaise: Long,
    val type: TransactionType,
    val paymentMode: PaymentMode,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val merchant: String? = null,
    val note: String? = null,
    val occurredAt: Long,
    val source: TransactionSource,
    val confidence: Float = 1f,
    val reviewStatus: ReviewStatus = ReviewStatus.NONE,
    val reference: String? = null,
    val balanceAfterPaise: Long? = null,
    val rawSmsSnippet: String? = null,
    val smsSender: String? = null,
    val dedupeKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "merchant_category_rules",
    indices = [Index("merchantKey", unique = true)]
)
data class MerchantCategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,
    val categoryId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sms_sender_rules")
data class SmsSenderRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderPattern: String,
    val allowed: Boolean = true,
    val bankHint: String? = null
)

@Entity(tableName = "import_jobs")
data class ImportJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val addedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val notes: String? = null
)
