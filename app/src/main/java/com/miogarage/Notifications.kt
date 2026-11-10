package com.miogarage

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

const val CHANNEL = "scadenze"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Scadenze", NotificationManager.IMPORTANCE_DEFAULT))
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "deadline-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeadlineWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

/** Daily check: notifies 30 days before, then every day of the last week, and for up to 30 days after expiry. */
class DeadlineWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return Result.success()

        val today = LocalDate.now().toEpochDay()
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        GarageDb.get(applicationContext).dao().allDeadlines().forEach { (d, targa) ->
            val left = daysLeft(d, today)
            if (left == 30L || left in -30..7) {
                val text = when {
                    left < 0 -> "Scaduta da ${-left} giorni"
                    left == 0L -> "Scade oggi"
                    else -> "Scade tra $left giorni"
                }
                val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("${d.tipo} · $targa")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                @Suppress("MissingPermission") // checked above
                NotificationManagerCompat.from(applicationContext).notify(d.id.toInt(), n)
            }
        }
        return Result.success()
    }
}
