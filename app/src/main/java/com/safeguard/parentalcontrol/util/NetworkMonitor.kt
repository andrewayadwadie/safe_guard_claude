package com.safeguard.parentalcontrol.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network connectivity monitor using reactive Flow-based approach
 *
 * Benefits over polling:
 * - Zero battery impact when network state doesn't change
 * - Instant notifications on network changes
 * - Clean reactive API with Flow
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Flow that emits network connectivity state changes
     * Emits true when connected, false when disconnected
     */
    val isConnected: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d("Network available")
                trySend(true)
            }

            override fun onLost(network: Network) {
                Timber.d("Network lost")
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Timber.d("Network capabilities changed: internet=$hasInternet, validated=$isValidated")
                trySend(hasInternet && isValidated)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Send initial state
        trySend(isCurrentlyConnected())

        // Register callback
        connectivityManager.registerNetworkCallback(request, callback)

        // Cleanup when flow collection stops
        awaitClose {
            Timber.d("Unregistering network callback")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Flow that emits detailed network state information
     */
    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkState())
            }

            override fun onLost(network: Network) {
                trySend(NetworkState.Disconnected)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(parseNetworkState(capabilities))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Send initial state
        trySend(getCurrentNetworkState())

        // Register callback
        connectivityManager.registerNetworkCallback(request, callback)

        // Cleanup when flow collection stops
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Check current connectivity state synchronously
     * Use for one-time checks; prefer isConnected Flow for reactive updates
     */
    fun isCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Get current network state synchronously
     */
    fun getCurrentNetworkState(): NetworkState {
        val network = connectivityManager.activeNetwork ?: return NetworkState.Disconnected
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkState.Disconnected
        return parseNetworkState(capabilities)
    }

    private fun parseNetworkState(capabilities: NetworkCapabilities): NetworkState {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!hasInternet || !isValidated) {
            return NetworkState.Disconnected
        }

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkState.Connected(ConnectionType.WIFI)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                NetworkState.Connected(ConnectionType.CELLULAR)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                NetworkState.Connected(ConnectionType.ETHERNET)
            }
            else -> {
                NetworkState.Connected(ConnectionType.OTHER)
            }
        }
    }
}

/**
 * Sealed class representing network connection states
 */
sealed class NetworkState {
    object Disconnected : NetworkState()
    data class Connected(val type: ConnectionType) : NetworkState()
}

/**
 * Enum representing connection types
 */
enum class ConnectionType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER
}
