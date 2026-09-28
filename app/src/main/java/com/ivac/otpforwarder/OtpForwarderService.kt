package com.ivac.otpforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OtpForwarderService : Service() {

    companion object {
        private const val TAG = "OtpForwarderService"
        private const val CHANNEL_ID = "ivac_otp_service_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private var dynamicSmsReceiver: SmsOtpReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerDynamicSmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY // System will restart service if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterDynamicSmsReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerDynamicSmsReceiver() {
        try {
            if (dynamicSmsReceiver == null) {
                dynamicSmsReceiver = SmsOtpReceiver()
                val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                    priority = 999
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.registerReceiver(
                        this,
                        dynamicSmsReceiver!!,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(dynamicSmsReceiver, filter)
                }
                Log.i(TAG, "Dynamic SMS Receiver successfully registered in foreground service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering dynamic SMS receiver", e)
        }
    }

    private fun unregisterDynamicSmsReceiver() {
        try {
            dynamicSmsReceiver?.let {
                unregisterReceiver(it)
                dynamicSmsReceiver = null
                Log.i(TAG, "Dynamic SMS Receiver unregistered from foreground service")
            }
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IVAC Dual-SIM OTP Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keep OTP forwarder running in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IVAC ডুয়াল-সিম ওটিপি ফরোয়ার্ডার সক্রিয়")
            .setContentText("সিম ১ এবং সিম ২ এর ওটিপি লাইভ রাউটিং মনিটর করা হচ্ছে...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
