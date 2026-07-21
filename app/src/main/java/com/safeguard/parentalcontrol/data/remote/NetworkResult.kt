package com.safeguard.parentalcontrol.data.remote

/**
 * Sealed class representing network request results
 * Provides type-safe handling of success, error, and loading states
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()

    val isSuccess: Boolean
        get() = this is Success

    val isError: Boolean
        get() = this is Error

    val isLoading: Boolean
        get() = this is Loading

    /**
     * Returns the data if success, or null otherwise
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    /**
     * Returns the data if success, or the default value otherwise
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        else -> default
    }

    /**
     * Returns the error message if error, or null otherwise
     */
    fun errorMessageOrNull(): String? = when (this) {
        is Error -> message
        else -> null
    }

    /**
     * Map the success data to a different type
     */
    inline fun <R> map(transform: (T) -> R): NetworkResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> Error(message, code)
        is Loading -> Loading
    }

    /**
     * Execute an action on success
     */
    inline fun onSuccess(action: (T) -> Unit): NetworkResult<T> {
        if (this is Success) action(data)
        return this
    }

    /**
     * Execute an action on error
     */
    inline fun onError(action: (String, Int?) -> Unit): NetworkResult<T> {
        if (this is Error) action(message, code)
        return this
    }

    /**
     * Execute an action on loading
     */
    inline fun onLoading(action: () -> Unit): NetworkResult<T> {
        if (this is Loading) action()
        return this
    }

    companion object {
        /**
         * Create a success result
         */
        fun <T> success(data: T): NetworkResult<T> = Success(data)

        /**
         * Create an error result
         */
        fun error(message: String, code: Int? = null): NetworkResult<Nothing> = Error(message, code)

        /**
         * Create a loading result
         */
        fun loading(): NetworkResult<Nothing> = Loading
    }
}

/**
 * Extension to handle API responses and convert to NetworkResult
 * Provides specific error messages for different failure types
 */
suspend fun <T> safeApiCall(apiCall: suspend () -> retrofit2.Response<T>): NetworkResult<T> {
    return try {
        val response = apiCall()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                NetworkResult.Success(body)
            } else {
                NetworkResult.Error("Empty response body", response.code())
            }
        } else {
            val errorMessage = parseErrorMessage(response)
            NetworkResult.Error(errorMessage, response.code())
        }
    } catch (e: java.net.UnknownHostException) {
        NetworkResult.Error("No internet connection. Please check your network.")
    } catch (e: java.net.SocketTimeoutException) {
        NetworkResult.Error("Connection timed out. Please try again.")
    } catch (e: java.net.ConnectException) {
        NetworkResult.Error("Unable to connect to server. Please try again later.")
    } catch (e: javax.net.ssl.SSLException) {
        NetworkResult.Error("Secure connection failed. Please check your network.")
    } catch (e: java.io.IOException) {
        NetworkResult.Error("Network error. Please check your connection.")
    } catch (e: Exception) {
        // Log unexpected errors for debugging (without sensitive data)
        timber.log.Timber.e(e, "Unexpected API error")
        NetworkResult.Error(e.message ?: "An unexpected error occurred")
    }
}

/**
 * Parse error message from response body
 */
private fun <T> parseErrorMessage(response: retrofit2.Response<T>): String {
    return try {
        val errorBody = response.errorBody()?.string()
        if (errorBody != null) {
            val json = org.json.JSONObject(errorBody)
            // FastAPI validation errors (422) return "detail" as an array of
            // {type, loc, msg} objects — surface the first entry's message.
            // Other errors (400/429/etc.) return "detail" as a plain string.
            val detailArray = json.optJSONArray("detail")
            if (detailArray != null && detailArray.length() > 0) {
                detailArray.optJSONObject(0)?.optString("msg")?.takeIf { it.isNotBlank() }
                    ?: "Request failed with code ${response.code()}"
            } else {
                json.optString("detail", "Request failed")
            }
        } else {
            "Request failed with code ${response.code()}"
        }
    } catch (e: Exception) {
        "Request failed with code ${response.code()}"
    }
}
