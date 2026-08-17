package com.fintracker.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fintracker.app.data.UserPreferences
import com.fintracker.app.domain.sms.SmsIngestionService
import com.fintracker.app.domain.sms.SmsMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SmsProcessWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ingestionService: SmsIngestionService,
    private val preferences: UserPreferences
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!preferences.autoCaptureEnabled.first()) return Result.success()
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, System.currentTimeMillis())
        if (body.isBlank()) return Result.success()
        ingestionService.ingest(SmsMessage(sender, body, receivedAt))
        return Result.success()
    }

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_RECEIVED_AT = "received_at"
    }
}
