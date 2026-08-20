package com.fintracker.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("fin_tracker_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val autoCaptureKey = booleanPreferencesKey("sms_auto_capture")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_done")
    private val lastScanAtKey = longPreferencesKey("last_sms_scan_at")
    private val localLlmKey = booleanPreferencesKey("local_category_llm")

    val autoCaptureEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[autoCaptureKey] ?: true }

    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[onboardingDoneKey] ?: false }

    val localLlmEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[localLlmKey] ?: true }

    suspend fun setAutoCapture(enabled: Boolean) {
        context.dataStore.edit { it[autoCaptureKey] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        context.dataStore.edit { it[onboardingDoneKey] = done }
    }

    suspend fun setLocalLlmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[localLlmKey] = enabled }
    }

    suspend fun setLastScanAt(time: Long) {
        context.dataStore.edit { it[lastScanAtKey] = time }
    }
}
