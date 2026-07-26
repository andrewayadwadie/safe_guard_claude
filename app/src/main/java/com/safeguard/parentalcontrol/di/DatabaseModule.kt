package com.safeguard.parentalcontrol.di

import android.content.Context
import androidx.room.Room
import com.safeguard.parentalcontrol.data.local.PendingAlertDao
import com.safeguard.parentalcontrol.data.local.SafeGuardDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the local database.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): SafeGuardDatabase = Room.databaseBuilder(
        context,
        SafeGuardDatabase::class.java,
        SafeGuardDatabase.NAME
    ).build()

    @Provides
    @Singleton
    fun providePendingAlertDao(database: SafeGuardDatabase): PendingAlertDao =
        database.pendingAlertDao()
}
