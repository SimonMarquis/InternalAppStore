package fr.smarquis.appstore

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build.VERSION_CODES.O
import android.os.Handler
import android.os.Looper
import android.support.annotation.WorkerThread
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat.IMPORTANCE_NONE
import android.support.v4.app.TaskStackBuilder
import android.support.v4.content.ContextCompat
import android.util.Log
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessagingService : FirebaseMessagingService() {

    companion object {

        private const val TAG = "MessagingService"

        fun cancelNotification(context: Context, tag: String, id: Int) {
            notificationManager(context).cancel(tag, id)
        }

        fun cancelAllNotifications(context: Context) {
            notificationManager(context).cancelAll()
        }

        fun createOrUpdateNewApplicationsNotificationChannel(context: Context) {
            if (Utils.isAtLeast(O)) {
                notificationManager(context).createNotificationChannel(NotificationChannel(context.getString(R.string.notification_channel_new_applications_id), context.getString(R.string.notification_channel_new_applications_name), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun createOrUpdateNewVersionsNotificationChannel(context: Context, channelId: String, applicationName: String?) {
            if (Utils.isAtLeast(O)) {
                val nm = notificationManager(context)
                if (nm.getNotificationChannel(channelId) == null) {
                    // If new apps notification is disabled, then create but disable this notification channel
                    val shouldMuteNotificationChannel = nm.getNotificationChannel(context.getString(R.string.notification_channel_new_applications_id))?.importance == IMPORTANCE_NONE
                    nm.createNotificationChannel(NotificationChannel(channelId, applicationName, if (shouldMuteNotificationChannel) IMPORTANCE_NONE else NotificationManager.IMPORTANCE_LOW))
                } else {
                    nm.createNotificationChannel(NotificationChannel(channelId, applicationName, NotificationManager.IMPORTANCE_LOW))
                }
            }
        }

        private fun notificationManager(context: Context): NotificationManager {
            return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
    }

    @WorkerThread
    override fun onMessageReceived(remoteMessage: RemoteMessage?) {
        if (remoteMessage == null) {
            return
        }
        Log.d(TAG, "onMessageReceived($remoteMessage)")
        val data = remoteMessage.data
        when (data["type"]) {
            "new_application" -> onNewApplication(remoteMessage)
            "new_version" -> onNewVersion(remoteMessage)
            else -> Log.e(TAG, "$TAG: Unknown message type [${data["type"]}]")
        }
    }

    private fun onNewApplication(msg: RemoteMessage) {
        Log.d(TAG, "onNewApplication()")
        val data = msg.data
        val applicationKey = data["applicationKey"] ?: return
        val applicationName = data["applicationName"]
        val applicationPackageName = data["applicationPackageName"]
        val applicationImage = data["applicationImage"]
        val application = Application(key = applicationKey, name = applicationName, packageName = applicationPackageName, image = applicationImage)

        val intent = VersionsActivity.intent(this, application)
        val pendingIntent = TaskStackBuilder.create(this).addNextIntentWithParentStack(intent).getPendingIntent(applicationKey.hashCode(), PendingIntent.FLAG_ONE_SHOT)
        val notificationBuilder = createNotificationBuilder(getString(R.string.notification_channel_new_applications_id), getString(R.string.notification_new_application_title), applicationName, msg.sentTime, pendingIntent)

        createOrUpdateNewApplicationsNotificationChannel(this)
        notifyWithImage(applicationKey, R.id.notification_new_application_id, notificationBuilder, application.findImageReference())
    }

    private fun onNewVersion(msg: RemoteMessage) {
        Log.d(TAG, "onNewVersion()")
        val data = msg.data
        val applicationKey = data["applicationKey"] ?: return
        val applicationName = data["applicationName"]
        val applicationPackageName = data["applicationPackageName"]
        val applicationImage = data["applicationImage"]
        val versionKey = data["versionKey"] ?: return
        val versionName = data["versionName"]
        val application = Application(key = applicationKey, name = applicationName, packageName = applicationPackageName, image = applicationImage)

        val intent = VersionsActivity.intent(this, application, versionKey)
        val pendingIntent = TaskStackBuilder.create(this).addNextIntentWithParentStack(intent).getPendingIntent(versionKey.hashCode(), PendingIntent.FLAG_ONE_SHOT)
        val channelId = getString(R.string.notification_channel_new_versions_id, applicationKey)
        val notificationBuilder = createNotificationBuilder(channelId, getString(R.string.notification_new_version_title), "$applicationName • $versionName", msg.sentTime, pendingIntent)

        createOrUpdateNewVersionsNotificationChannel(this, channelId, applicationName)
        notifyWithImage(applicationKey, R.id.notification_new_version_id, notificationBuilder, application.findImageReference())
    }

    private fun createNotificationBuilder(channelId: String, title: String, text: String?, timestamp: Long, pendingIntent: PendingIntent?): NotificationCompat.Builder {
        val now = System.currentTimeMillis()
        return NotificationCompat.Builder(this, channelId)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSmallIcon(R.drawable.ic_shop_24dp)
                .setColor(ContextCompat.getColor(this, R.color.colorAccent))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setWhen(if (timestamp <= 0) now else Math.min(timestamp, now))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
    }

    private fun notifyWithImage(tag: String, id: Int, notificationBuilder: NotificationCompat.Builder, image: Any?) {
        val notificationManager = notificationManager(this)
        notificationManager.notify(tag, id, notificationBuilder.build())
        if (image == null) {
            return
        }
        val width = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val height = resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        Handler(Looper.getMainLooper()).post {
            GlideApp.with(applicationContext).asBitmap().apply(DEFAULT_APPLICATION_IMAGE_REQUEST_OPTIONS).override(width, height).load(image)
                    .into(object : SimpleTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            val notification = notificationBuilder.setLargeIcon(resource).build()
                            notificationManager.notify(tag, id, notification)
                        }
                    })
        }
    }

}