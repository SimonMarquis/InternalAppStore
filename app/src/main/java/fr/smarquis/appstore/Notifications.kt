package fr.smarquis.appstore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.firebase.messaging.RemoteMessage

class Notifications {

    companion object {
        fun cancel(context: Context, tag: String, id: Int) {
            notificationManager(context).cancel(tag, id)
        }

        fun cancelAll(context: Context) {
            notificationManager(context).cancelAll()
        }

        fun createOrUpdateNewApplicationsNotificationChannel(context: Context) {
            if (Utils.isAtLeast(Build.VERSION_CODES.O)) {
                notificationManager(context).createNotificationChannel(NotificationChannel(context.getString(R.string.notification_channel_new_applications_id), context.getString(R.string.notification_channel_new_applications_name), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun newVersionsNotificationChannelId(context: Context, application: Application): String =
                context.getString(R.string.notification_channel_new_versions_id, application.key)

        fun createOrUpdateNewVersionsNotificationChannel(context: Context, application: Application) {
            if (Utils.isAtLeast(Build.VERSION_CODES.O)) {
                val nm = notificationManager(context)
                val channelId = newVersionsNotificationChannelId(context, application)
                if (nm.getNotificationChannel(channelId) == null) {
                    // If new apps notification is disabled, then create but disable this notification channel
                    val shouldMuteNotificationChannel = nm.getNotificationChannel(context.getString(R.string.notification_channel_new_applications_id))?.importance == NotificationManagerCompat.IMPORTANCE_NONE
                    nm.createNotificationChannel(NotificationChannel(channelId, application.name, if (application.isMyself(context)) NotificationManager.IMPORTANCE_HIGH else if (shouldMuteNotificationChannel) NotificationManagerCompat.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_LOW))
                } else {
                    nm.createNotificationChannel(NotificationChannel(channelId, application.name, if (application.isMyself(context)) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW))
                }
            }
        }

        fun onNewApplication(context: Context, application: Application, msg: RemoteMessage? = null) {
            val intent = VersionsActivity.intent(context, application)
            val pendingIntent = TaskStackBuilder.create(context).addNextIntentWithParentStack(intent).getPendingIntent(application.key.orEmpty().hashCode(), PendingIntent.FLAG_UPDATE_CURRENT)
            val notificationBuilder = createNotificationBuilder(
                    context = context,
                    channelId = context.getString(R.string.notification_channel_new_applications_id),
                    title = context.getString(R.string.notification_new_application_title),
                    text = application.name,
                    timestamp = msg?.sentTime ?: System.currentTimeMillis(),
                    pendingIntent = pendingIntent)

            createOrUpdateNewApplicationsNotificationChannel(context)
            notifyWithImage(
                    context = context,
                    tag = application.key.orEmpty(),
                    id = R.id.notification_new_application_id,
                    notificationBuilder = notificationBuilder,
                    image = application.findImageReference()
            )
        }

        fun onNewVersion(context: Context, application: Application, version: Version, msg: RemoteMessage? = null) {
            val intent = VersionsActivity.intent(context, application, version.key)
            val pendingIntent = TaskStackBuilder.create(context).addNextIntentWithParentStack(intent).getPendingIntent(version.key.orEmpty().hashCode(), PendingIntent.FLAG_UPDATE_CURRENT)
            val myself = application.isMyself(context)
            val notificationBuilder = createNotificationBuilder(
                    context = context,
                    channelId = newVersionsNotificationChannelId(context, application),
                    priority = if (myself) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT,
                    title = context.getString(R.string.notification_new_version_title),
                    text = "${application.name} • ${version.name}",
                    autoCancel = !myself,
                    timestamp = msg?.sentTime ?: version.timestamp ?: System.currentTimeMillis(),
                    pendingIntent = pendingIntent)

            createOrUpdateNewVersionsNotificationChannel(context, application)
            notifyWithImage(
                    context = context,
                    // Use a unique tag for the current app to avoid cancelling the notification when opening the corresponding Activity
                    tag = application.key.orEmpty() + if (application.isMyself(context)) "_permanent" else "",
                    id = R.id.notification_new_version_id,
                    notificationBuilder = notificationBuilder,
                    image = application.findImageReference())
        }

        private fun createNotificationBuilder(context: Context, channelId: String, priority: Int = NotificationCompat.PRIORITY_DEFAULT, title: String, text: String?, autoCancel: Boolean = true, timestamp: Long, pendingIntent: PendingIntent?): NotificationCompat.Builder {
            val now = System.currentTimeMillis()
            return NotificationCompat.Builder(context, channelId)
                    .setPriority(priority)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setSmallIcon(R.drawable.ic_shop_24dp)
                    .setColor(ContextCompat.getColor(context, R.color.colorAccent))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pendingIntent)
                    .setWhen(if (timestamp <= 0) now else Math.min(timestamp, now))
                    .setAutoCancel(autoCancel)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
        }

        private fun notifyWithImage(context: Context, tag: String, id: Int, notificationBuilder: NotificationCompat.Builder, image: Any?) {
            val notificationManager = notificationManager(context)
            notificationManager.notify(tag, id, notificationBuilder.build())
            if (image == null) {
                return
            }
            val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
            val requestOptions = RequestOptions().override(width, height).diskCacheStrategy(DiskCacheStrategy.ALL).centerCrop()
            Handler(Looper.getMainLooper()).post {
                Glide.with(context).asBitmap().apply(requestOptions).load(image)
                        .into(object : SimpleTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                val notification = notificationBuilder.setLargeIcon(resource).build()
                                notificationManager.notify(tag, id, notification)
                            }
                        })
            }
        }

        private fun notificationManager(context: Context): NotificationManager {
            return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
    }
}

