package com.safeguard.parentalcontrol.data.account

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.service.MonitoringService
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrappers over the platform side effects of an account teardown. It exists so the teardown
 * order can be unit-tested with fakes. Every method swallows and logs its own failure: one step
 * failing must never stop the ones after it, and none of them may throw into the caller.
 */
@Singleton
class DeviceTeardownOps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpCache: Cache,
    private val firebaseAnalytics: FirebaseAnalytics
) {
    fun cancelAllWork() = step("cancelAllWork") {
        WorkManager.getInstance(context).cancelAllWork()
    }

    /** Cancels the AlarmManager restart that `MonitoringService.scheduleServiceRestart` arms. */
    fun cancelMonitoringRestartAlarm() = step("cancelMonitoringRestartAlarm") {
        val pending = PendingIntent.getService(
            context,
            MONITORING_RESTART_REQUEST_CODE,
            Intent(context, MonitoringService::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }

    fun stopMonitoringService() = step("stopMonitoringService") {
        if (MonitoringService.isRunning) {
            context.startService(MonitoringService.getStopIntent(context))
        }
    }

    /**
     * Sends the VPN service's existing ACTION_STOP. The caller must have switched the
     * content-filtering preference off first so the service's own teardown raises no tamper alert.
     */
    fun stopVpn() = step("stopVpn") {
        if (ContentFilterVpnService.isActive || ContentFilterVpnService.isVpnRunning(context)) {
            context.startService(
                Intent(context, ContentFilterVpnService::class.java)
                    .setAction(ContentFilterVpnService.ACTION_STOP)
            )
        }
    }

    fun evictHttpCache() = step("evictHttpCache") { okHttpCache.evictAll() }

    /** Blurred-image originals live here. The blurred copies in the gallery are left as they are. */
    fun deleteImageBackups() = step("deleteImageBackups") {
        File(context.filesDir, IMAGE_BACKUP_DIR).deleteRecursively()
    }

    fun cancelAllNotifications() = step("cancelAllNotifications") {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Fire and forget: the server-side registration died with the account. */
    fun deleteFcmToken() = step("deleteFcmToken") {
        FirebaseMessaging.getInstance().deleteToken()
    }

    fun resetAnalytics() = step("resetAnalytics") { firebaseAnalytics.resetAnalyticsData() }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            Timber.tag(TAG).i("ops step=%s ok", name)
        } catch (e: Exception) {
            Timber.tag(TAG).w("ops step=%s failed: %s", name, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "AccountDeletion"
        const val MONITORING_RESTART_REQUEST_CODE = 1
        const val IMAGE_BACKUP_DIR = "image_backup"
    }
}
