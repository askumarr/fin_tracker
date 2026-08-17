package com.fintracker.app.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fintracker.app.data.UserPreferences
import com.fintracker.app.domain.sms.SmsInboxScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val inboxScanner: SmsInboxScanner
) : ViewModel() {
    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage = _scanMessage.asStateFlow()

    fun complete() {
        viewModelScope.launch {
            preferences.setOnboardingDone(true)
            preferences.setAutoCapture(true)
        }
    }

    fun scan(days: Int) {
        viewModelScope.launch {
            _scanMessage.value = "Scanning last $days days…"
            val result = inboxScanner.scanRecent(TimeUnit.DAYS.toMillis(days.toLong()))
            preferences.setLastScanAt(System.currentTimeMillis())
            _scanMessage.value =
                "Scanned ${result.scanned} SMS · imported ${result.imported} · duplicates ${result.duplicates}"
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var days by remember { mutableIntStateOf(30) }
    val scanMessage by viewModel.scanMessage.collectAsStateWithLifecycle()
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result[Manifest.permission.READ_SMS] == true ||
            result[Manifest.permission.RECEIVE_SMS] == true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "FinTracker",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Set SMS permission once — expenses track themselves.",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "We read bank and UPI transaction SMS on this device only. " +
                        "Amounts stay local. You can export an encrypted backup anytime.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Text("Backfill from inbox", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 90).forEach { d ->
                            FilterChip(
                                selected = days == d,
                                onClick = { days = d },
                                label = { Text("$d days") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!permissionGranted) {
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_SMS,
                                        Manifest.permission.RECEIVE_SMS
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Allow SMS access") }
                    } else {
                        Button(
                            onClick = { viewModel.scan(days) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Scan last $days days") }
                    }
                    scanMessage?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = {
                        viewModel.complete()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue") }
                OutlinedButton(
                    onClick = {
                        viewModel.complete()
                        onFinished()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Skip for now") }
            }
        }
    }
}
