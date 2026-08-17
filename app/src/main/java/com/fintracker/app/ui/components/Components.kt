package com.fintracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.domain.model.PaymentMode
import com.fintracker.app.domain.model.TransactionType
import com.fintracker.app.ui.util.DateFormatters
import com.fintracker.app.ui.util.MoneyFormat

@Composable
fun TransactionRow(txn: TransactionEntity, categoryName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = txn.merchant ?: txn.note ?: "Transaction",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = listOfNotNull(
                    categoryName,
                    txn.paymentMode.name.replace('_', ' '),
                    DateFormatters.day(txn.occurredAt)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = when (txn.type) {
                TransactionType.EXPENSE -> "−"
                TransactionType.INCOME -> "+"
                TransactionType.TRANSFER -> "⇄ "
            } + MoneyFormat.formatPaise(txn.amountPaise),
            color = when (txn.type) {
                TransactionType.EXPENSE -> MaterialTheme.colorScheme.error
                TransactionType.INCOME -> MaterialTheme.colorScheme.primary
                TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SummaryPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 10.dp else 16.dp)
    ) {
        Text(
            text = label,
            style = if (compact) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(if (compact) 2.dp else 6.dp))
        Text(
            text = value,
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * Chip labels must never wrap: a chip squeezed by its parent would otherwise break a long label
 * such as "Net banking" into one character per line.
 */
@Composable
fun ChipLabel(text: String) {
    Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
}

/** "NET_BANKING" -> "Net banking", keeping acronyms such as UPI upper-cased. */
fun paymentModeLabel(mode: PaymentMode): String = when (mode) {
    PaymentMode.UPI -> "UPI"
    PaymentMode.DEBIT_CARD -> "Debit card"
    PaymentMode.CREDIT_CARD -> "Credit card"
    PaymentMode.NET_BANKING -> "Net banking"
    PaymentMode.CASH -> "Cash"
    PaymentMode.UNKNOWN -> "Other"
}
