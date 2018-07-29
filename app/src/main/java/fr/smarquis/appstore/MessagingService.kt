package fr.smarquis.appstore

import android.util.Log
import androidx.annotation.WorkerThread
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessagingService : FirebaseMessagingService() {

    companion object {

        private const val TAG = "MessagingService"

    }

    override fun onNewToken(token: String?) {
        Log.d(TAG, "Refreshed token: $token")
    }

    @WorkerThread
    override fun onMessageReceived(remoteMessage: RemoteMessage?) {
        if (remoteMessage == null) {
            return
        }
        val data = remoteMessage.data
        when (data["type"]) {
            "new_application" -> onNewApplication(remoteMessage)
            "new_version" -> onNewVersion(remoteMessage)
            else -> Log.e(TAG, "$TAG: Unknown message type [${data["type"]}]")
        }
    }

    private fun onNewApplication(msg: RemoteMessage) {
        val data = msg.data
        val application = Application(
                key = data["applicationKey"] ?: return,
                name = data["applicationName"],
                packageName = data["applicationPackageName"],
                image = data["applicationImage"])
        Notifications.onNewApplication(this, application, msg)
    }

    private fun onNewVersion(msg: RemoteMessage) {
        val data = msg.data
        val application = Application(
                key = data["applicationKey"] ?: return,
                name = data["applicationName"],
                packageName = data["applicationPackageName"],
                image = data["applicationImage"])
        val version = Version(
                key = data["versionKey"] ?: return,
                name = data["versionName"])
        Notifications.onNewVersion(this, application, version, msg)
    }

}