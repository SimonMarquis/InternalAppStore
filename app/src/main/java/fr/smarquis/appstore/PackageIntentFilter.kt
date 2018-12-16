package fr.smarquis.appstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.*
import android.content.IntentFilter
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

@Keep
class PackageIntentFilter(private val activity: AppCompatActivity, private val block: (action: String, packageName: String) -> Unit) : BroadcastReceiver(), LifecycleObserver {

    companion object {

        fun init(activity: AppCompatActivity, block: (action: String, packageName: String) -> Unit) = PackageIntentFilter(activity, block)

        private val INTENT_FILTER = object : IntentFilter() {
            init {
                addAction(ACTION_PACKAGE_ADDED)
                addAction(ACTION_PACKAGE_REPLACED)
                addAction(ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        }

    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        intent.data?.schemeSpecificPart?.let {
            if (INTENT_FILTER.hasAction(action)) {
                block(action, it)
            }
        }
    }

    @Suppress("unused")
    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    private fun register() {
        activity.registerReceiver(this, INTENT_FILTER)
    }

    @Suppress("unused")
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun unregister() {
        activity.unregisterReceiver(this)
    }

}