package fr.smarquis.appstore

import android.support.v7.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.IntentFilter

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
                    intent.data.schemeSpecificPart?.let {
                        if (INTENT_FILTER.hasAction(intent.action)) {
                            block(intent.action, it)
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