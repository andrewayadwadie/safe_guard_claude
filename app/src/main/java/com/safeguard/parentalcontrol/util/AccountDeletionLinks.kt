package com.safeguard.parentalcontrol.util

import com.safeguard.parentalcontrol.BuildConfig

object AccountDeletionLinks {
    private const val API_SUFFIX = "/api/v1"
    private const val PAGE_PATH = "/account-deletion"

    /**
     * The public "How account deletion works" page, the same URL registered in Play Console. It is
     * derived from the API base URL (the host root, without the `/api/v1` suffix), so there is no
     * separate build-config field to keep in sync per build type.
     */
    fun publicDeletionPageUrl(baseUrl: String = BuildConfig.API_BASE_URL): String {
        val root = baseUrl.trim().trimEnd('/').removeSuffix(API_SUFFIX).trimEnd('/')
        return root + PAGE_PATH
    }
}
