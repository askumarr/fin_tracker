package com.fintracker.app.di

import android.content.Context
import androidx.room.Room
import com.fintracker.app.data.dao.AccountDao
import com.fintracker.app.data.dao.CategoryDao
import com.fintracker.app.data.dao.ImportJobDao
import com.fintracker.app.data.dao.MerchantCategoryRuleDao
import com.fintracker.app.data.dao.SmsSenderRuleDao
import com.fintracker.app.data.dao.TransactionDao
import com.fintracker.app.data.db.DatabaseSeeder
import com.fintracker.app.data.db.FinTrackerDatabase
import com.fintracker.app.data.repository.AccountRepository
import com.fintracker.app.domain.sms.SmsParseEngine
import com.fintracker.app.domain.sms.SmsTemplateLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FinTrackerDatabase =
        Room.databaseBuilder(context, FinTrackerDatabase::class.java, FinTrackerDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideCategoryDao(db: FinTrackerDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideAccountDao(db: FinTrackerDatabase): AccountDao = db.accountDao()
    @Provides fun provideTransactionDao(db: FinTrackerDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideMerchantRuleDao(db: FinTrackerDatabase): MerchantCategoryRuleDao =
        db.merchantCategoryRuleDao()
    @Provides fun provideSmsSenderRuleDao(db: FinTrackerDatabase): SmsSenderRuleDao =
        db.smsSenderRuleDao()
    @Provides fun provideImportJobDao(db: FinTrackerDatabase): ImportJobDao = db.importJobDao()

    @Provides
    @Singleton
    fun provideSeeder(
        categoryDao: CategoryDao,
        accountRepository: AccountRepository
    ): DatabaseSeeder = DatabaseSeeder(categoryDao, accountRepository)

    @Provides
    @Singleton
    fun provideSmsTemplateLoader(@ApplicationContext context: Context): SmsTemplateLoader =
        SmsTemplateLoader(context)

    @Provides
    @Singleton
    fun provideSmsParseEngine(loader: SmsTemplateLoader): SmsParseEngine =
        SmsParseEngine { loader.load() }
}