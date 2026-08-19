package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class CallkitNotificationService : Service() {

    companion object {

        private const val TAG = "CallkitNotificationService"

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT,
            CallkitConstants.ACTION_CALL_INCOMING
        )


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                data?.let {
                    // MOJIAPP FORK: dolazno zvono ide u foreground bez obzira na
                    // EXTRA_CALLKIT_CALLING_SHOW (taj flag se tice ongoing ekrana).
                    if (action == CallkitConstants.ACTION_CALL_INCOMING ||
                        it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

        // MOJIAPP FORK: dolazno zvono na Androidu 14+ zivi kao FGS
        // notifikacija; obican NotificationManager.cancel() je ne mice dok je
        // servis foreground. Receiver, manager i servis su isti proces, pa
        // manager gasi foreground stanje direktnim pozivom.
        @Volatile
        private var instance: CallkitNotificationService? = null

        fun onIncomingCleared(isAccepted: Boolean) {
            instance?.let {
                if (it.foregroundIsIncoming) {
                    ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    it.foregroundIsIncoming = false
                    // accept: isti servis odmah dize ongoing startForeground;
                    // decline/timeout: servis vise nema razloga zivjeti
                    if (!isAccepted) it.stopSelf()
                }
            }
        }

    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }


    private var foregroundIsIncoming = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bundle = intent?.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        val isForegroundAction = intent?.action in ActionForeground
        // MOJIAPP FORK: incoming NE ide kroz placeholder — placeholder se postavlja
        // pod ongoing notification id-em, pa bi nakon pravog incoming
        // startForeground-a (drugi id) ostao visjeti kao "Calling..." duh.
        val isIncomingAction = intent?.action == CallkitConstants.ACTION_CALL_INCOMING
        val needsPlaceholder = isForegroundAction && !isIncomingAction &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bundle?.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true) == true
        if (needsPlaceholder) {
            if (!showPlaceholderForegroundNotification(bundle)) {
                return START_NOT_STICKY
            }
        }

        try {
            when (intent?.action) {
                CallkitConstants.ACTION_CALL_START -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                getCallkitNotificationManager()?.createNotificationChanel(it)
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                CallkitConstants.ACTION_CALL_ACCEPT -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                // MOJIAPP FORK: dolazno zvono kao FGS (Android 14+ odbija CallStyle
                // notifikaciju koja ne dolazi iz FGS-a ili full-screen intenta, a
                // USE_FULL_SCREEN_INTENT nam je ugasen).
                CallkitConstants.ACTION_CALL_INCOMING -> {
                    val incoming = bundle?.let {
                        getCallkitNotificationManager()?.getIncomingNotification(it)
                    }
                    if (incoming != null) {
                        // Samo PHONE_CALL tip: dok zvoni mikrofon se jos ne koristi,
                        // a MICROPHONE FGS tip iz pozadine Android 14 odbija
                        // (while-in-use ogranicenje).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                incoming.id,
                                incoming.notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                            )
                        } else {
                            startForeground(incoming.id, incoming.notification)
                        }
                        foregroundIsIncoming = true
                    } else {
                        // startForegroundService BEZ startForeground = 
                        // ForegroundServiceDidNotStartInTimeException; dignemo
                        // upstream placeholder pa se odmah ugasimo.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            showPlaceholderForegroundNotification(bundle)
                        }
                        stopSelf()
                    }
                }

                null -> {
                    // OS restarted the service after kill (START_STICKY with null intent).
                    // Flutter engine may not be running yet — create a standalone manager
                    // using only Context so we can restore startForeground() without the plugin.
                    val activeCalls = getDataActiveCalls(this)
                    val bundle = activeCalls.firstOrNull()?.toBundle()
                    if (bundle != null) {
                        val pluginManager = getCallkitNotificationManager()
                        val manager = pluginManager
                            ?: CallkitNotificationManager(this, CallkitSoundPlayerManager(this))
                        manager.createNotificationChanel(bundle)
                        val notification = manager.getOnGoingCallNotification(bundle, false)
                        if (notification != null) {
                            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
                            startForeground(
                                notification.id,
                                notification.notification,
                                typeCall > 0
                            )
                            if (pluginManager == null) manager.destroy()
                        } else {
                            if (pluginManager == null) manager.destroy()
                            stopSelf()
                        }
                    } else {
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show the ongoing call notification", e)
            if (needsPlaceholder || isIncomingAction) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle) {

        val callkitNotification =
            getCallkitNotificationManager()?.getOnGoingCallNotification(bundle, false)
        if (callkitNotification != null) {
            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
            startForeground(
                callkitNotification.id,
                callkitNotification.notification,
                typeCall > 0
            )
        }
    }

    private fun getOnGoingNotificationId(bundle: Bundle): Int {
        return bundle.getString(
            CallkitConstants.EXTRA_CALLKIT_CALLING_ID,
            bundle.getString(CallkitConstants.EXTRA_CALLKIT_ID, "callkit_incoming")
        ).hashCode()
    }

    private fun showPlaceholderForegroundNotification(bundle: Bundle?): Boolean {
        val notificationId = bundle?.let { getOnGoingNotificationId(it) }
            ?: "callkit_incoming".hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING,
                        "Ongoing Call",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val smallIcon =
            applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming

        val notification =
            androidx.core.app.NotificationCompat.Builder(
                this,
                CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING
            )
                .setContentTitle(applicationInfo.loadLabel(packageManager))
                .setContentText("Calling...")
                .setSmallIcon(smallIcon)
                .setOngoing(true)
                .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground with the placeholder notification", e)
            stopSelf()
            false
        }
    }

    private fun startForeground(notificationId: Int, notification: Notification, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Since Android 14 (API 34), starting a foreground service with the
                // MICROPHONE or CAMERA type throws a SecurityException unless the
                // corresponding runtime permission is already granted. Only add the
                // types whose permission is granted (e.g. the user may answer a call
                // before the app ever requested RECORD_AUDIO).
                if (isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (isVideo && isPermissionGranted(Manifest.permission.CAMERA)) {
                    mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            startForeground(notificationId, notification, mask)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED


    override fun onDestroy() {
        instance = null
        super.onDestroy()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't kill the FGS. The app might be closed by user but the call is still ongoing
    }
}
