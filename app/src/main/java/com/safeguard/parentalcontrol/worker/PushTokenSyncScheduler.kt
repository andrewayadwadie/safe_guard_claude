package com.safeguard.parentalcontrol.worker

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules push-token publication.
 *
 * A thin injectable seam over [PushTokenSyncWorker]: callers depend on this rather than
 * reaching WorkManager statically, which keeps ViewModels free of platform singletons and
 * unit-testable without a WorkManager runtime.
 */
@Singleton
class PushTokenSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() = PushTokenSyncWorker.enqueue(context)
}
