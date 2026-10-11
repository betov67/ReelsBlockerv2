package com.example.reelsblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * BlockerForegroundService  v2
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Keeps the app process alive with a high-priority foreground notification so
 * that aggressive OEM battery savers (MIUI, HyperOS, EMUI, OneUI, ColorOS, etc.)
 * do not suspend it when the screen goes off or Instagram is in the foreground.
 *
 * ── v2 changes ──────────────────────────────────────────────────────────────
 *
 *  1. startForeground() is called in BOTH onCreate() AND onStartCommand():
 *     • onCreate()       → guarantees the service becomes foreground the instant
 *                          the OS creates it, before any IPC call can arrive.
 *     • onStartCommand() → re-promotes after the OS restarts it via START_STICKY
 *                          (the restart delivers a null Intent; this guard ensures
 *                          the service never runs unnoticed as background).
 *
 *  2. Notification channel uses IMPORTANCE_HIGH (was IMPORTANCE_LOW).
 *     Several OEMs (MIUI, HyperOS in particular) deprioritise and eventually
 *     kill services whose notification channel is IMPORTANCE_LOW or IMPORTANCE_MIN,
 *     treating them as "silent background apps".  IMPORTANCE_HIGH keeps the
 *     process in the top-priority foreground bucket.
 *     The notification itself still has no sound — we override the sound to null
 *     and set the priority to PRIORITY_LOW so the user doesn't get an audible
 *     alert, while the *channel* importance keeps the OEM process scheduler happy.
 *
 *  3. We set Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT
 *     explicitly on the built Notification object (some OEMs ignore setOngoing).
 *
 *  4. Return START_STICKY from onStartCommand() so Android (and OEM launchers
 *     that respect it) automatically restart the service if killed.
 */
class BlockerForegroundService : Service() {

    companion object {
        private const val TAG             = "ReelsBlockerSvc"
        const val CHANNEL_ID              = "reels_blocker_v2"
        const val NOTIFICATION_ID         = 1001
        const val ACTION_STOP             = "com.example.reelsblocker.ACTION_STOP"
    }

    // ─── onCreate ─────────────────────────────────────────────────────────────
    // Called once when the service object is first instantiated.
    // We start foreground HERE (not only in onStartCommand) to guarantee the
    // shortest possible window between process creation and foreground promotion.

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — promoting to foreground immediately")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    // ─── onStartCommand ───────────────────────────────────────────────────────
    // Called every time startForegroundService() is invoked, and also by the OS
    // when it auto-restarts a START_STICKY service (with intent == null).

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received — shutting down")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Re-promote to foreground on every start (handles OS restart with null intent)
        Log.i(TAG, "onStartCommand — re-promoting to foreground (startId=$startId)")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // START_STICKY: OS will restart the service if it kills the process.
        // The restart delivers intent=null, which falls through to the
        // createNotificationChannel/startForeground block above.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification channel ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        // IMPORTANCE_HIGH keeps the process in the system's foreground bucket on
        // aggressive OEMs (MIUI / HyperOS / EMUI). We suppress the sound below.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description          = getString(R.string.notification_channel_desc)
            setSound(null, null) // Silent despite IMPORTANCE_HIGH
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .setOngoing(true)              // user cannot swipe it away
            .setAutoCancel(false)
            // PRIORITY_LOW makes the notification appear in the drawer without
            // a heads-up banner, while the channel's IMPORTANCE_HIGH keeps the
            // OEM process priority high.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Belt-and-suspenders: some OEMs check raw flags rather than the compat wrappers.
        @Suppress("DEPRECATION")
        notification.flags = notification.flags or
                Notification.FLAG_ONGOING_EVENT or
                Notification.FLAG_NO_CLEAR

        return notification
    }
}
