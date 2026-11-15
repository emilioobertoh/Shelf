package app.shelf.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.shelf.R
import app.shelf.data.Reminder
import app.shelf.shelf
import app.shelf.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The morning reminder: a daily alarm that posts the reading goal and the book in progress.
 * Tapping it opens Shelf straight into that book.
 */
object ReadingReminder {
    // A channel's importance can't be raised after it's created, so the pop-up version has a new id.
    private const val CHANNEL_ID = "reading_reminder_popup"
    private const val OLD_CHANNEL_ID = "reading_reminder"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_FIRE = "app.shelf.action.REMINDER"

    const val ACTION_OPEN_BOOK = "app.shelf.action.OPEN_BOOK"
    const val EXTRA_BOOK_ID = "book_id"

    fun createChannel(context: Context) {
        // High importance makes it pop up on screen (a "heads-up" notification).
        val channel = NotificationChannel(CHANNEL_ID, "Reading reminder", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A morning reminder of your daily reading goal." }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(channel)
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets (or clears) the alarm for the next time the reminder is due. */
    fun schedule(context: Context, reminder: Reminder) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        if (!reminder.enabled) return

        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(reminder.hour, reminder.minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            // Without exact alarms Android may deliver it up to an hour late.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Builds and posts the notification. [force] posts it even when today's goal is already met. */
    suspend fun show(context: Context, force: Boolean = false) {
        if (!canNotify(context)) return
        val shelf = context.shelf
        val goal = shelf.settings.goals.first().dailyMinutes
        val todayMinutes = (shelf.db.sessions().secondsOn(LocalDate.now().toEpochDay()) / 60).toInt()
        if (todayMinutes >= goal && !force) return
        val book = shelf.db.books().lastReading()

        val title = "Your reading goal: $goal minutes"
        val text = when {
            book != null -> "Pick up “${book.title}” where you left off · ${(book.progress * 100).roundToInt()}% read"
            else -> "Open Shelf and start a book."
        }

        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_BOOK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        book?.let { open.putExtra(EXTRA_BOOK_ID, it.id) }
        val tap = PendingIntent.getActivity(
            context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setColor(0xFFE8772E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setLargeIcon(book?.coverPath?.let(::loadCover))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the post.
        }
    }

    private fun loadCover(path: String): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

    /** Called when the alarm fires and when the clock, time zone, app or phone restarts. */
    internal fun onReceive(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        val shelf = context.shelf
        shelf.appScope.launch {
            try {
                val reminder = shelf.settings.reminder.first()
                if (intent.action == ACTION_FIRE && reminder.enabled) show(context)
                schedule(context, reminder)
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReadingReminder.onReceive(context, intent, goAsync())
    }
}
