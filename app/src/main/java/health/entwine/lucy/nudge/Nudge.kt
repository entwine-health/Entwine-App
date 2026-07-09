// Daily local nudge (R-NOT-01..03, DD-09): exact alarm + WorkManager fallback,
// suppressed when a session already happened today, one tap → ready state.
package health.entwine.lucy.nudge

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import health.entwine.lucy.MainActivity
import health.entwine.lucy.R
import health.entwine.lucy.store.Store
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val CHANNEL_ID = "lucy_nudge"
private const val REQ = 4201

object NudgeScheduler {
    /** (Re)schedule the next nudge at the quiet-moment time (R-NOT-01). */
    fun schedule(ctx: Context, quietTime: LocalTime) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var at = LocalDateTime.of(LocalDate.now(), quietTime)
        if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
        val trigger = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, NudgeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) // graceful fallback
        }
    }

    fun cancelToday(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, NudgeReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) am.cancel(pi)
    }
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store(ctx)
        val today = LocalDate.now().toString()
        val hadSession = runBlocking { store.lastSessionDay() } == today
        val quiet = runBlocking { store.quietTime() }
        if (!hadSession) show(ctx) // R-NOT-02: suppressed if a session happened today
        if (quiet != null) NudgeScheduler.schedule(ctx, quiet) // tomorrow's alarm
    }

    private fun show(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Entwine", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).setAction("health.entwine.lucy.OPEN_READY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            1,
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(ctx.getString(R.string.nudge_title))
                .setContentText(ctx.getString(R.string.nudge_text))
                .setContentIntent(open) // one tap → ready (R-NOT-03)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val quiet = runBlocking { Store(ctx).quietTime() } ?: return
        NudgeScheduler.schedule(ctx, quiet) // alarms don't survive reboot
    }
}
