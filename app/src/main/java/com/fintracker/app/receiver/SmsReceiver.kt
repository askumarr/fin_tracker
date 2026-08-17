package com.fintracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fintracker.app.worker.SmsProcessWorker

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val receivedAt = messages.first().timestampMillis

        val work = OneTimeWorkRequestBuilder<SmsProcessWorker>()
            .setInputData(
                workDataOf(
                    SmsProcessWorker.KEY_SENDER to sender,
                    SmsProcessWorker.KEY_BODY to body,
                    SmsProcessWorker.KEY_RECEIVED_AT to receivedAt
                )
            )
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
