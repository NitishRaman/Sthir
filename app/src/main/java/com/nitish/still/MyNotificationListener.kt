package com.nitish.still

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MyNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MyNotificationListener? = null
    }

    private val TAG = "MyNotificationListener"
    private var mediaSessionMgr: MediaSessionManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionMgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        Log.d(TAG, "NotificationListener onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "NotificationListener onDestroy")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // optional: track posted notifications if useful for UI
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // optional
    }

    /**
     * Attempt to pause media playback for a given package.
     * Returns true if we invoked pause on at least one controller.
     */
    fun pausePackageIfActive(packageName: String): Boolean {
        try {
            val mgr = mediaSessionMgr ?: run {
                Log.w(TAG, "pausePackageIfActive: mediaSessionMgr null")
                return false
            }

            // use our own component so system will return sessions visible to this listener
            val comp = ComponentName(this, MyNotificationListener::class.java)
            val controllers: List<MediaController> = mgr.getActiveSessions(comp)

            var pausedSomething = false
            for (c in controllers) {
                try {
                    val pkg = c.packageName
                    if (pkg == packageName) {
                        Log.d(TAG, "Found active MediaController for $packageName; sending pause()")
                        c.transportControls.pause()
                        pausedSomething = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pausePackageIfActive: controller pause failed: ${e.message}")
                }
            }

            if (!pausedSomething) {
                Log.d(TAG, "pausePackageIfActive: no active controller found for $packageName (controllers=${controllers.size})")
            }
            return pausedSomething
        } catch (t: Throwable) {
            Log.w(TAG, "pausePackageIfActive error: ${t.message}")
            return false
        }
    }
}
