package com.safeguard.parentalcontrol.data.remote

import com.safeguard.parentalcontrol.data.model.*
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API service interface for Haris backend
 * Matches all 28 endpoints from the backend API
 */
interface ApiService {

    // ==================== Authentication Endpoints ====================

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<TokenResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<TokenResponse>

    /**
     * Synchronous refresh token endpoint for use by AuthInterceptor
     * This is needed because interceptors run synchronously
     */
    @POST("auth/refresh")
    fun refreshTokenSync(@Body request: RefreshTokenRequest): Call<TokenResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<MessageResponse>

    @GET("auth/me")
    suspend fun getCurrentUser(): Response<User>

    @POST("auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<MessageResponse>

    // ==================== OAuth Endpoints ====================

    // NOTE: GET oauth/google/status removed in Edit 2 â€” the Google button is always shown
    // (FR-017/FR-018), so the status check is no longer called.

    /**
     * Authenticate or register user via Google Sign-In
     * - If user exists with Google ID: Log them in
     * - If email exists with local auth: Link Google account and log in
     * - If new user: Register with provided role (required)
     */
    @POST("oauth/google")
    suspend fun googleAuth(@Body request: GoogleAuthRequest): Response<TokenResponse>

    // ==================== Device Management Endpoints ====================

    @POST("devices")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): Response<Device>

    @GET("devices")
    suspend fun getDevices(
        @Query("child_id") childId: Int? = null
    ): Response<List<Device>>

    @GET("devices/{deviceId}")
    suspend fun getDevice(@Path("deviceId") deviceId: Int): Response<Device>

    @PUT("devices/{deviceId}")
    suspend fun updateDevice(
        @Path("deviceId") deviceId: Int,
        @Body request: DeviceUpdateRequest
    ): Response<Device>

    @DELETE("devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: Int): Response<MessageResponse>

    @POST("devices/{deviceId}/sync")
    suspend fun syncDevice(
        @Path("deviceId") deviceId: Int,
        @Query("utc_offset_minutes") utcOffsetMinutes: Int? = null
    ): Response<MessageResponse>

    @POST("devices/{deviceId}/suspend")
    suspend fun suspendDevice(@Path("deviceId") deviceId: Int): Response<Device>

    @POST("devices/{deviceId}/activate")
    suspend fun activateDevice(@Path("deviceId") deviceId: Int): Response<Device>

    // ==================== Screen Time Endpoints ====================

    @POST("screen-time/{deviceId}")
    suspend fun updateScreenTime(
        @Path("deviceId") deviceId: Int,
        @Body request: ScreenTimeUpdate
    ): Response<ScreenTimeLog>

    @GET("screen-time/{deviceId}")
    suspend fun getScreenTimeLogs(
        @Path("deviceId") deviceId: Int,
        @Query("days") days: Int = 7
    ): Response<List<ScreenTimeLog>>

    @GET("screen-time/{deviceId}/stats")
    suspend fun getScreenTimeStats(
        @Path("deviceId") deviceId: Int,
        @Query("days") days: Int = 30
    ): Response<ScreenTimeStats>

    @POST("screen-time/{deviceId}/app-usage")
    suspend fun updateAppUsage(
        @Path("deviceId") deviceId: Int,
        @Body request: AppUsageBatch
    ): Response<MessageResponse>

    @GET("screen-time/{deviceId}/app-usage")
    suspend fun getAppUsageLogs(
        @Path("deviceId") deviceId: Int,
        @Query("days") days: Int = 7,
        @Query("package_name") packageName: String? = null
    ): Response<List<AppUsageLog>>

    @GET("screen-time/{deviceId}/app-usage/today")
    suspend fun getTodayAppUsage(
        @Path("deviceId") deviceId: Int
    ): Response<List<AppUsageLog>>

    // ==================== Alert Endpoints ====================

    @POST("alerts")
    suspend fun createAlert(
        @Body request: AlertCreate,
        @Header("X-Device-Token") deviceToken: String
    ): Response<Alert>

    @GET("alerts")
    suspend fun getAlerts(
        @Query("device_id") deviceId: Int? = null,
        @Query("alert_type") alertType: String? = null,
        @Query("severity") severity: String? = null,
        @Query("is_read") isRead: Boolean? = null,
        @Query("is_dismissed") isDismissed: Boolean? = null,
        @Query("days") days: Int = 30,
        @Query("limit") limit: Int = 50
    ): Response<List<Alert>>

    @GET("alerts/stats")
    suspend fun getAlertStats(
        @Query("device_id") deviceId: Int? = null,
        @Query("days") days: Int = 30
    ): Response<AlertStats>

    @GET("alerts/{alertId}")
    suspend fun getAlert(@Path("alertId") alertId: Int): Response<Alert>

    @PUT("alerts/{alertId}")
    suspend fun updateAlert(
        @Path("alertId") alertId: Int,
        @Body request: AlertUpdateRequest
    ): Response<Alert>

    @POST("alerts/mark-all-read")
    suspend fun markAllAlertsRead(
        @Query("device_id") deviceId: Int? = null
    ): Response<MessageResponse>

    @DELETE("alerts/{alertId}")
    suspend fun deleteAlert(@Path("alertId") alertId: Int): Response<MessageResponse>

    // ==================== Content Filter Endpoints ====================

    @GET("content-filter/{deviceId}")
    suspend fun getContentFilter(@Path("deviceId") deviceId: Int): Response<ContentFilter>

    @POST("content-filter/{deviceId}")
    suspend fun createContentFilter(
        @Path("deviceId") deviceId: Int,
        @Body request: ContentFilterUpdate
    ): Response<ContentFilter>

    @PUT("content-filter/{deviceId}")
    suspend fun updateContentFilter(
        @Path("deviceId") deviceId: Int,
        @Body request: ContentFilterUpdate
    ): Response<ContentFilter>

    @GET("content-filter/{deviceId}/blacklist")
    suspend fun getBlacklist(@Path("deviceId") deviceId: Int): Response<BlacklistResponse>

    @POST("content-filter/{deviceId}/blacklist/add")
    suspend fun addToBlacklist(
        @Path("deviceId") deviceId: Int,
        @Body request: BlacklistDomainRequest
    ): Response<BlacklistResponse>

    @POST("content-filter/{deviceId}/blacklist/remove")
    suspend fun removeFromBlacklist(
        @Path("deviceId") deviceId: Int,
        @Body request: BlacklistDomainRequest
    ): Response<BlacklistResponse>

    @DELETE("content-filter/{deviceId}/blacklist")
    suspend fun clearBlacklist(@Path("deviceId") deviceId: Int): Response<MessageResponse>

    // ==================== Custom Word List Endpoints ====================

    /**
     * Get all custom word lists (parent only)
     */
    @GET("word-lists")
    suspend fun getWordLists(): Response<CustomWordListResponse>

    /**
     * Add a new word to whitelist or blacklist (parent only)
     */
    @POST("word-lists")
    suspend fun addWord(@Body request: CustomWordCreateRequest): Response<CustomWordResponse>

    /**
     * Add multiple words at once (parent only)
     */
    @POST("word-lists/bulk")
    suspend fun addWordsBulk(@Body request: CustomWordBulkCreateRequest): Response<List<CustomWordResponse>>

    /**
     * Update a custom word entry (parent only)
     */
    @PUT("word-lists/{wordId}")
    suspend fun updateWord(
        @Path("wordId") wordId: Int,
        @Body request: CustomWordUpdateRequest
    ): Response<CustomWordResponse>

    /**
     * Delete a custom word entry (parent only)
     */
    @DELETE("word-lists/{wordId}")
    suspend fun deleteWord(@Path("wordId") wordId: Int): Response<MessageResponse>

    /**
     * Clear all words from a specific list or all lists (parent only)
     */
    @DELETE("word-lists")
    suspend fun clearWordList(
        @Query("list_type") listType: String? = null
    ): Response<MessageResponse>

    /**
     * Sync word lists for device (child device only)
     * Uses X-Device-Token header for authentication
     */
    @GET("word-lists/sync")
    suspend fun syncWordLists(
        @Header("X-Device-Token") deviceToken: String
    ): Response<CustomWordSyncResponse>

    // ==================== Family Link Endpoints ====================

    /**
     * Generate a short-lived pairing code for THIS child account (child only).
     * The child shows the code; a parent enters it to link.
     */
    @POST("family/pairing-code")
    suspend fun createPairingCode(): Response<PairingCodeResponse>

    /**
     * Link a child to this parent by redeeming a pairing code (parent only)
     */
    @POST("family/link")
    suspend fun createFamilyLink(@Body request: FamilyLinkCreateRequest): Response<FamilyLink>

    /**
     * Get all linked children (parent only)
     */
    @GET("family/children")
    suspend fun getLinkedChildren(): Response<List<FamilyLink>>

    /** Get all parents linked to this child (child only). Uses the user Bearer token. */
    @GET("family/parents")
    suspend fun getLinkedParents(): Response<List<LinkedParent>>

    /**
     * Get specific family link details
     */
    @GET("family/link/{childId}")
    suspend fun getFamilyLink(@Path("childId") childId: Int): Response<FamilyLink>

    /**
     * Remove family link with a child (parent only)
     */
    @DELETE("family/link/{childId}")
    suspend fun removeFamilyLink(@Path("childId") childId: Int): Response<MessageResponse>

    // ==================== Screen Time Rules Endpoints ====================

    /**
     * Get screen time rules for a device
     */
    @GET("screen-time-rules/{deviceId}")
    suspend fun getScreenTimeRules(@Path("deviceId") deviceId: Int): Response<ScreenTimeRule>

    /**
     * Create screen time rules for a device (parent only)
     */
    @POST("screen-time-rules/{deviceId}")
    suspend fun createScreenTimeRules(
        @Path("deviceId") deviceId: Int,
        @Body request: ScreenTimeRuleCreateRequest
    ): Response<ScreenTimeRule>

    /**
     * Update screen time rules (parent only)
     */
    @PUT("screen-time-rules/{deviceId}")
    suspend fun updateScreenTimeRules(
        @Path("deviceId") deviceId: Int,
        @Body request: ScreenTimeRuleUpdateRequest
    ): Response<ScreenTimeRule>

    /**
     * Delete screen time rules (parent only)
     */
    @DELETE("screen-time-rules/{deviceId}")
    suspend fun deleteScreenTimeRules(@Path("deviceId") deviceId: Int): Response<MessageResponse>

    /**
     * Add or update a per-app time limit (parent only)
     */
    @POST("screen-time-rules/{deviceId}/app-limit")
    suspend fun addAppLimit(
        @Path("deviceId") deviceId: Int,
        @Query("package_name") packageName: String,
        @Query("limit_seconds") limitSeconds: Int
    ): Response<ScreenTimeRule>

    /**
     * Remove a per-app time limit (parent only)
     */
    @DELETE("screen-time-rules/{deviceId}/app-limit/{packageName}")
    suspend fun removeAppLimit(
        @Path("deviceId") deviceId: Int,
        @Path("packageName") packageName: String
    ): Response<ScreenTimeRule>

    /**
     * Block an app on child's device (parent only)
     */
    @POST("screen-time-rules/{deviceId}/blocked-app")
    suspend fun addBlockedApp(
        @Path("deviceId") deviceId: Int,
        @Query("package_name") packageName: String
    ): Response<ScreenTimeRule>

    /**
     * Unblock an app on child's device (parent only)
     */
    @DELETE("screen-time-rules/{deviceId}/blocked-app/{packageName}")
    suspend fun removeBlockedApp(
        @Path("deviceId") deviceId: Int,
        @Path("packageName") packageName: String
    ): Response<ScreenTimeRule>

    // ==================== Health Check ====================

    @GET("/health")
    suspend fun healthCheck(): Response<HealthResponse>
}
