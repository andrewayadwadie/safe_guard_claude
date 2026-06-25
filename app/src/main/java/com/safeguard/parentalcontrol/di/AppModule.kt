package com.safeguard.parentalcontrol.di

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.ml.ContentClassifier
import com.safeguard.parentalcontrol.util.NetworkMonitor
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for application-wide dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideFirebaseAnalytics(
        @ApplicationContext context: Context
    ): FirebaseAnalytics = FirebaseAnalytics.getInstance(context)

    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager {
        return TokenManager(context)
    }

    @Provides
    @Singleton
    fun providePreferencesManager(
        @ApplicationContext context: Context
    ): PreferencesManager {
        return PreferencesManager(context)
    }

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor {
        return NetworkMonitor(context)
    }

    @Provides
    @Singleton
    fun provideCustomWordRepository(
        @ApplicationContext context: Context,
        apiService: ApiService,
        preferencesManager: PreferencesManager
    ): CustomWordRepository {
        return CustomWordRepository(context, apiService, preferencesManager)
    }

    @Provides
    @Singleton
    fun provideContentClassifier(
        @ApplicationContext context: Context,
        customWordRepository: CustomWordRepository
    ): ContentClassifier {
        return ContentClassifier(context, customWordRepository)
    }
}
