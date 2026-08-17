package com.fintracker.app.domain.backup

import com.fintracker.app.data.entity.AccountEntity
import com.fintracker.app.data.entity.CategoryEntity
import com.fintracker.app.data.entity.MerchantCategoryRuleEntity
import com.fintracker.app.data.entity.TransactionEntity
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.data.repository.CategoryRepository
import com.fintracker.app.data.repository.ImportJobRepository
import com.fintracker.app.data.repository.TransactionRepository
import com.google.gson.Gson
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val categories: List<CategoryEntity>,
    val accounts: List<AccountEntity>,
    val transactions: List<TransactionEntity>,
    val merchantRules: List<MerchantCategoryRuleEntity>
)

@Singleton
class BackupService @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val importJobRepository: ImportJobRepository
) {
    private val gson = Gson()

    suspend fun exportEncrypted(passphrase: CharArray, output: OutputStream) {
        val payload = BackupPayload(
            categories = categoryRepository.getAll(),
            accounts = accountRepository.getAll(),
            transactions = transactionRepository.getAllForBackup(),
            merchantRules = importJobRepository.getMerchantRules()
        )
        val json = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(encrypt(json, passphrase))
        output.flush()
    }

    suspend fun importEncrypted(passphrase: CharArray, input: InputStream) {
        val all = input.readBytes()
        require(all.size > MAGIC.length + 28) { "Invalid backup file" }
        val magic = all.copyOfRange(0, MAGIC.length).toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Not a FinTracker encrypted backup" }
        val decrypted = decrypt(all.copyOfRange(MAGIC.length, all.size), passphrase)
        val payload = gson.fromJson(String(decrypted, Charsets.UTF_8), BackupPayload::class.java)
        restoreCategories(payload.categories)
        restoreAccounts(payload.accounts)
        transactionRepository.replaceAll(payload.transactions)
        importJobRepository.replaceMerchantRules(payload.merchantRules)
    }

    suspend fun exportPlainCsv(output: OutputStream) {
        val txns = transactionRepository.getAllForBackup()
        output.write("id,date,type,amount_inr,mode,merchant,note,source,reference\n".toByteArray())
        txns.forEach { t ->
            val amount = t.amountPaise / 100.0
            val line = listOf(
                t.id,
                t.occurredAt,
                t.type.name,
                amount,
                t.paymentMode.name,
                escape(t.merchant),
                escape(t.note),
                t.source.name,
                escape(t.reference)
            ).joinToString(",") + "\n"
            output.write(line.toByteArray())
        }
        output.flush()
    }

    private suspend fun restoreCategories(categories: List<CategoryEntity>) {
        val existing = categoryRepository.getAll().associateBy { it.name.lowercase() }
        categories.forEach { cat ->
            if (!existing.containsKey(cat.name.lowercase())) {
                categoryRepository.insert(cat.copy(id = 0))
            }
        }
    }

    private suspend fun restoreAccounts(accounts: List<AccountEntity>) {
        val existing = accountRepository.getAll().associateBy { it.name.lowercase() }
        accounts.forEach { acc ->
            if (!existing.containsKey(acc.name.lowercase())) {
                accountRepository.insert(acc.copy(id = 0))
            }
        }
    }

    private fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return salt + iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        val salt = blob.copyOfRange(0, 16)
        val iv = blob.copyOfRange(16, 28)
        val cipherText = blob.copyOfRange(28, blob.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, 120_000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    companion object {
        const val MAGIC = "FTBK1"
    }
}
