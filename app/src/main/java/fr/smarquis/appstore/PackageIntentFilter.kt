package fr.smarquis.appstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity

class PackageIntentFilter {

    companion object {

        private val INTENT_FILTER = object : IntentFilter() {
            init {
                addAction(ACTION_PACKAGE_ADDED)
                addAction(ACTION_PACKAGE_REPLACED)
                addAction(ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        }

        fun receiver(block: (action: String, packageName: String) -> Unit): BroadcastReceiver {
            return object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action ?: return
                    intent.data?.schemeSpecificPart?.let {
                        if (INTENT_FILTER.hasAction(action)) {
                            block(action, it)
                        }
                    }
                }
            }
        }

        fun register(activity: AppCompatActivity, receiver: BroadcastReceiver) {
            activity.registerReceiver(receiver, INTENT_FILTER)
        }

        fun unregister(activity: AppCompatActivity, receiver: BroadcastReceiver) {
            activity.unregisterReceiver(receiver)
        }
    }

}