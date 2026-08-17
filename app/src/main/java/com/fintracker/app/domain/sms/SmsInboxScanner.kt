package com.fintracker.app.domain.sms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsInboxScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingestionService: SmsIngestionService
) {
    data class ScanResult(val scanned: Int, val imported: Int, val duplicates: Int)

    suspend fun scanRecent(windowMs: Long): ScanResult {
        val since = System.currentTimeMillis() - windowMs
        var scanned = 0
        var imported = 0
        var duplicates = 0
        val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val selection = "${Telephony.Sms.DATE} >= ?"
        val args = arrayOf(since.toString())
        context.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned++
                val message = cursor.toSmsMessage()
                val result = ingestionService.ingest(message)
                when {
                    result.duplicate -> duplicates++
                    result.savedId != null -> imported++
                }
            }
        }
        return ScanResult(scanned, imported, duplicates)
    }

    private fun Cursor.toSmsMessage(): SmsMessage {
        val sender = getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty()
        val body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty()
        val date = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE))
        return SmsMessage(sender = sender, body = body, receivedAt = date)
    }
}
