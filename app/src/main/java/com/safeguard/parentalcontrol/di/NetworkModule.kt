package com.safeguard.parentalcontrol.di

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.AuthInterceptor
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.FlexibleDateAdapter
import java.util.Date
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module for network dependencies (Retrofit, OkHttp)
 * Includes security enhancements: certificate pinning, log redaction, caching
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            // Use flexible date adapter to handle various ISO 8601 formats from backend
            // Backend returns: 2026-01-22T14:31:00.165515 (microseconds, no timezone)
            .registerTypeAdapter(Date::class.java, FlexibleDateAdapter())
            .create()
    }

    /**
     * Provides logging interceptor with sensitive data redaction
     * Redacts passwords, tokens, and other sensitive fields from logs
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        val logger = HttpLoggingInterceptor.Logger { message ->
            val redactedMessage = redactSensitiveData(message)
            Timber.tag("OkHttp").d(redactedMessage)
        }

        return HttpLoggingInterceptor(logger).apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    /**
     * Redacts sensitive data from log messages
     * Prevents credential leakage in debug logs
     */
    private fun redactSensitiveData(message: String): String {
        return message
            .replace(Regex("\"password\"\\s*:\\s*\"[^\"]+\""), "\"password\":\"[REDACTED]\"")
            .replace(Regex("\"current_password\"\\s*:\\s*\"[^\"]+\""), "\"current_password\":\"[REDACTED]\"")
            .replace(Regex("\"new_password\"\\s*:\\s*\"[^\"]+\""), "\"new_password\":\"[REDACTED]\"")
            .replace(Regex("\"access_token\"\\s*:\\s*\"[^\"]+\""), "\"access_token\":\"[REDACTED]\"")
            .replace(Regex("\"refresh_token\"\\s*:\\s*\"[^\"]+\""), "\"refresh_token\":\"[REDACTED]\"")
            .replace(Regex("\"accessToken\"\\s*:\\s*\"[^\"]+\""), "\"accessToken\":\"[REDACTED]\"")
            .replace(Regex("\"refreshToken\"\\s*:\\s*\"[^\"]+\""), "\"refreshToken\":\"[REDACTED]\"")
            .replace(Regex("Authorization:\\s*Bearer\\s+[A-Za-z0-9\\-_\\.]+"), "Authorization: Bearer [REDACTED]")
            .replace(Regex("X-Device-Token:\\s*[A-Za-z0-9\\-]+"), "X-Device-Token: [REDACTED]")
    }

    /**
     * Certificate pinning for production builds
     * Prevents MITM attacks by validating server certificates
     *
     * IMPORTANT: Replace placeholder pins with actual certificate hashes before production deployment
     * Generate pins using: openssl s_client -servername api.safeguard.app -connect api.safeguard.app:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
     */
    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner {
        return if (!BuildConfig.DEBUG) {
            CertificatePinner.Builder()
                // Primary certificate pin - replace with your actual certificate hash
                .add(Constants.API_HOST, "sha256/${Constants.CERTIFICATE_PIN_PRIMARY}")
                // Backup certificate pin for rotation - replace with backup certificate hash
                .add(Constants.API_HOST, "sha256/${Constants.CERTIFICATE_PIN_BACKUP}")
                .build()
        } else {
            // No pinning in debug builds for easier development
            CertificatePinner.Builder().build()
        }
    }

    /**
     * HTTP cache for improved performance and offline support
     */
    @Provides
    @Singleton
    fun provideOkHttpCache(
        @ApplicationContext context: Context
    ): Cache {
        val cacheSize = 10L * 1024L * 1024L // 10 MB
        return Cache(File(context.cacheDir, "http_cache"), cacheSize)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        certificatePinner: CertificatePinner,
        cache: Cache
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .cache(cache)
            .connectTimeout(Constants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(Constants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Enable certificate pinning in production builds
        if (!BuildConfig.DEBUG) {
            builder.certificatePinner(certificatePinner)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
