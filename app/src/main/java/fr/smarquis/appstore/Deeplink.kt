package fr.smarquis.appstore

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.TaskStackBuilder

class Deeplink : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        val data = intent.data ?: return
        val application = when {
            data.scheme == getString(R.string.deeplink_scheme) && data.host.isNullOrBlank().not() -> Application(data.host)
            data.scheme == "https" && data.host == getString(R.string.deeplink_host) && data.pathSegments.size == 1 -> Application(data.lastPathSegment)
            else -> null
        }
        if (application == null) {
            ApplicationsActivity.start(this@Deeplink)
            return
        }
        val intent = VersionsActivity.intent(
                context = this@Deeplink,
                application = application,
                highlightVersionKey = data.fragment,
                unknown = true
        )
        TaskStackBuilder.create(this@Deeplink).addNextIntentWithParentStack(intent).startActivities()
    }
}