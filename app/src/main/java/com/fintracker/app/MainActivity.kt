package com.fintracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.fintracker.app.data.UserPreferences
import com.fintracker.app.ui.navigation.FinTrackerNavHost
import com.fintracker.app.ui.theme.FinTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val onboardingDone by userPreferences.onboardingDone.collectAsState(initial = true)
            FinTrackerTheme {
                FinTrackerNavHost(startOnboarding = !onboardingDone)
            }
        }
        // Warm preferences
        lifecycleScope.launch { userPreferences.onboardingDone.first() }
    }
}
