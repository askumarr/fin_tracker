package com.fintracker.app.ui.screens.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.domain.recurring.RecurringDetectionService
import com.fintracker.app.domain.recurring.RecurringPattern
import com.fintracker.app.ui.util.DateFormatters
import com.fintracker.app.ui.util.MoneyFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RecurringViewModel @Inject constructor(
    recurringDetectionService: RecurringDetectionService
) : ViewModel() {
    val patterns = flow {
        emit(recurringDetectionService.detect())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(viewModel: RecurringViewModel = hiltViewModel()) {
    val patterns by viewModel.patterns.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Recurring") }) }) { padding ->
        if (patterns.isEmpty()) {
            Text(
                "No recurring patterns yet. Need at least 3 similar expenses over ~6 months " +
                    "(SIP, rent, EMI, subscriptions).",
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(patterns, key = { it.merchantKey + it.cadence }) { pattern ->
                RecurringRow(pattern)
            }
        }
    }
}

@Composable
private fun RecurringRow(pattern: RecurringPattern) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pattern.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${pattern.cadence} · ${pattern.occurrences} times · last " +
                    DateFormatters.day(pattern.lastOccurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            MoneyFormat.formatPaise(pattern.amountPaise),
            fontWeight = FontWeight.SemiBold
        )
    }
}
