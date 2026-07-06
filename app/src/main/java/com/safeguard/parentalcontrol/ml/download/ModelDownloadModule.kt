package com.safeguard.parentalcontrol.ml.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.safeguard.parentalcontrol.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Singleton

/**
 * Production wiring for {@link ModelDownloader}: OkHttp streaming transport + a
 * ConnectivityManager-backed metered-connection check, and the filesDir/models target the
 * classifier reads from. Kept out of the testable core so unit tests need no Android Context.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModelDownloadModule {

    @Provides
    @Singleton
    fun provideModelTransport(client: OkHttpClient): ModelTransport =
        OkHttpModelTransport(client)

    @Provides
    @Singleton
    fun provideNetworkConditions(@ApplicationContext context: Context): NetworkConditions =
        AndroidNetworkConditions(context)

    @Provides
    @Singleton
    fun provideModelDownloader(
        @ApplicationContext context: Context,
        transport: ModelTransport,
        network: NetworkConditions,
    ): ModelDownloader = ModelDownloader(
        modelsDir = File(context.filesDir, "models"),
        baseUrl = BuildConfig.API_BASE_URL,
        transport = transport,
        network = network,
    )
}

/** OkHttp-backed streaming download with progress callbacks. Blocking IO — call on Dispatchers.IO. */
private class OkHttpModelTransport(private val client: OkHttpClient) : ModelTransport {
    override suspend fun get(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
            val body = response.body ?: throw IOException("empty body for $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }
}

/** Treats Wi-Fi / Ethernet / any NOT_METERED transport as eligible for large downloads. */
private class AndroidNetworkConditions(private val context: Context) : NetworkConditions {
    override fun isUnmetered(): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            Timber.w(e, "isUnmetered check failed; treating as metered")
            false
        }
    }
}
