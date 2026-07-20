// Foreground keep-alive service for reply playback (to_solve #19e).
//
// The problem: with no foreground service, Android suspends a backgrounded app's
// threads and network — so switching away (or the screen turning off) mid-reply
// froze the off-main playback drain and dropped the turn WebSocket, and Lucy's
// remaining audio was lost. The reducer already KEEPS the Responding state across
// AppBackground (Machine.kt) — only OS process suspension killed the audio.
//
// This service runs ONLY while Lucy is speaking and carries no audio of its own;
// it exists purely to hold the process at foreground priority so the existing
// Player (audio/Audio.kt) and SessionClient keep running until the reply ends.
// The mic still stops in the background (AppBackground -> MIC_OFF/DROP_MIC,
// unchanged) — privacy is untouched; only playback is kept alive.
package health.entwine.lucy.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import health.entwine.lucy.R

class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.playback_notif_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // minSdk 31 → the typed startForeground + FOREGROUND_SERVICE_MEDIA_PLAYBACK
        // permission (manifest) are always available.
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY // don't resurrect after a kill; a reply is transient
    }

    companion object {
        private const val CHANNEL = "lucy_playback"
        private const val NOTIF_ID = 1042

        /** Keep the process alive while Lucy speaks. Safe to call when already
         *  running (re-delivers onStartCommand). The caller wraps this: a start
         *  from the background can be refused on API 12+ (the rare text-turn-then-
         *  immediate-background case), which degrades to the old behaviour rather
         *  than crashing. */
        fun start(ctx: Context) {
            ensureChannel(ctx)
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        ctx.getString(R.string.playback_channel_name),
                        NotificationManager.IMPORTANCE_LOW, // silent, no heads-up
                    )
                )
            }
        }
    }
}
