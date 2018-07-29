package fr.smarquis.appstore

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build.VERSION_CODES.N
import android.os.Bundle
import android.text.SpannedString
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.italic
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.OnProgressListener
import fr.smarquis.appstore.Version.Status.*
import fr.smarquis.appstore.VersionRequest.Companion.create
import java.io.File
import java.lang.ref.WeakReference

class VersionsActivity : AppCompatActivity() {

    companion object {

        private const val TAG = "AppStore"

        private const val EXTRA_APPLICATION = "EXTRA_APPLICATION"
        private const val EXTRA_HIGHLIGHT_VERSION_KEY = "EXTRA_HIGHLIGHT_VERSION_KEY"

        fun start(context: Context, application: Application) {
            context.startActivity(intent(context, application))
        }

        fun start(activity: AppCompatActivity, application: Application, vararg sharedElement: Pair<View, String>) {
            val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, *sharedElement).toBundle()
            activity.startActivity(intent(activity, application), bundle)
        }

        fun intent(context: Context, application: Application, highlightVersionKey: String? = null): Intent {
            return Intent(context, VersionsActivity::class.java).apply {
                putExtra(EXTRA_APPLICATION, application)
                highlightVersionKey?.let {
                    putExtra(EXTRA_HIGHLIGHT_VERSION_KEY, it)
                }
            }
        }
    }

    private var firebaseApplicationDatabaseRef: DatabaseReference? = null
    private var versionAdapter: VersionAdapter? = null
    private var application: Application? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar

    private val applicationValueEventListener: ValueEventListener = object : ValueEventListener {
        override fun onCancelled(error: DatabaseError) {
            error.let {
                Log.w(TAG, "Error fetching application ${application?.key}\n${it.message}\n${it.details}", error.toException())
                Toast.makeText(this@VersionsActivity, it.message, LENGTH_LONG).show()
                finish()
            }
        }

        override fun onDataChange(snapshot: DataSnapshot) {
            Application.parse(snapshot).let {
                if (it == null) {
                    Toast.makeText(applicationContext, R.string.versions_toast_application_removed, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Notifications.createOrUpdateNewVersionsNotificationChannel(this@VersionsActivity, it)
                    updateApplication(it)
                }
            }
        }
    }

    private val dataObserver: RecyclerView.AdapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            contentLoadingProgressBar.hide()

            // Restore active downloads (progress and complete)
            for (position in positionStart until positionStart + itemCount) {
                val version = versionAdapter?.getItem(position) ?: continue
                version.getActiveDownloadTask()?.apply { DownloadProgressListener.update(snapshot, version, versionAdapter) }
                        ?.addOnProgressListener(DownloadProgressListener(this@VersionsActivity, version))
                        ?.addOnCompleteListener(DownloadCompleteListener(this@VersionsActivity, version, primary = false))
            }
        }
    }

    private val packageIntentFilterReceiver = PackageIntentFilter.receiver { _: String, packageName: String ->
        if (packageName == application?.packageName) {
            updateApplication(application)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val application: Application? = intent.extras.getParcelable(EXTRA_APPLICATION)
        if (application?.key == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_versions)
        initUi(application)
        registerListeners(application)
        updateApplication(application)
    }

    override fun onStart() {
        super.onStart()
        application?.key?.let {
            Notifications.cancel(this, it, R.id.notification_new_application_id)
            Notifications.cancel(this, it, R.id.notification_new_version_id)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        unregisterListeners()
        super.onDestroy()
    }

    private fun registerListeners(application: Application) {
        firebaseApplicationDatabaseRef = Firebase.database.application(application.key.orEmpty())
        firebaseApplicationDatabaseRef?.addValueEventListener(applicationValueEventListener)
        versionAdapter?.registerAdapterDataObserver(dataObserver)
        versionAdapter?.startListening()
        PackageIntentFilter.register(this, packageIntentFilterReceiver)
    }

    private fun unregisterListeners() {
        firebaseApplicationDatabaseRef?.removeEventListener(applicationValueEventListener)
        PackageIntentFilter.unregister(this, packageIntentFilterReceiver)
        versionAdapter?.unregisterAdapterDataObserver(dataObserver)
        versionAdapter?.stopListening()
    }

    private fun initUi(application: Application) {
        ViewCompat.setTransitionName(findViewById(R.id.imageView_header_icon), application.imageTransitionName())
        recyclerView = findViewById(R.id.recyclerView_versions)
        contentLoadingProgressBar = findViewById(R.id.contentLoadingProgressBar_versions)
        contentLoadingProgressBar.show()

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            elevation = 0F
        }

        versionAdapter = VersionAdapter(
                query = Firebase.database.versions(application.key.orEmpty()),
                callback = object : VersionAdapter.Callback {
                    override fun onItemClicked(version: Version, versionViewHolder: VersionViewHolder) {
                        downloadVersion(application, version, false)
                    }

                    override fun onItemLongClicked(version: Version, versionViewHolder: VersionViewHolder): Boolean {
                        downloadVersion(application, version, true)
                        return true
                    }
                }
        ).apply {
            // Scroll to the highlighted version
            intent.extras?.getString(EXTRA_HIGHLIGHT_VERSION_KEY)?.let {
                intent.extras?.remove(EXTRA_HIGHLIGHT_VERSION_KEY)
                // provide adapter with the version key to highlight
                highlightVersion(it)
                // detect highlighted version insertion and scroll to position
                registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        for (position in positionStart until positionStart + itemCount) {
                            if (it == (versionAdapter?.getItem(position) ?: break).key) {
                                recyclerView.scrollToPosition(position)
                                unregisterAdapterDataObserver(this)
                                return
                            }
                        }
                    }
                })
            }
        }

        recyclerView.apply {
            val orientation = RecyclerView.VERTICAL
            layoutManager = LinearLayoutManager(this@VersionsActivity, orientation, false)
            adapter = versionAdapter
            setHasFixedSize(true)
            addItemDecoration(DividerItemDecoration(context, orientation))
        }
    }

    private fun updateApplication(application: Application?): Boolean {
        if (application?.key == null) {
            finish()
            return false
        }
        this.application = application
        updateAppTitle(application)
        updateAppIcon(application)
        updateAppDescription(application)
        updateAppLinks(application)
        invalidateOptionsMenu()
        return true
    }

    private fun updateAppTitle(application: Application) {
        supportActionBar?.apply {
            title = application.name
            subtitle = application.packageName
        }
    }

    private fun updateAppIcon(application: Application) {
        application.loadImageInto(findViewById(R.id.imageView_header_icon), GlideApp.with(this))
    }

    private fun updateAppDescription(application: Application) {
        val textView: TextView = findViewById(R.id.textView_header_description)
        val textView2: TextView = findViewById(R.id.textView_header_installedInfo)
        val appInstalled = Utils.isApplicationInstalled(this, application.packageName.orEmpty())
        textView2.visibility = if (appInstalled) VISIBLE else GONE
        if (appInstalled) {
            val appVersionName = Utils.applicationVersionName(this, application.packageName.orEmpty())
            val appVersionCode = Utils.applicationVersionCode(this, application.packageName.orEmpty())
            textView2.text = getString(R.string.versions_header_installed_info, appVersionName, appVersionCode)
        } else {
            textView2.text = null
        }
        textView.text = application.descriptionToHtml
        textView.visibility = if (application.descriptionToHtml.isNullOrBlank()) GONE else VISIBLE
    }

    private fun updateAppLinks(application: Application) {
        updateAppLink(application.link_1, findViewById(R.id.button_header_link1))
        updateAppLink(application.link_2, findViewById(R.id.button_header_link2))
        updateAppLink(application.link_3, findViewById(R.id.button_header_link3))
        updateAppLink(application.link_4, findViewById(R.id.button_header_link4))
        findViewById<HorizontalScrollView>(R.id.horizontalScrollView_header).apply {
            visibility = if (application.link_1 == null && application.link_2 == null && application.link_3 == null && application.link_4 == null) GONE else VISIBLE
        }
    }

    private fun updateAppLink(link: Link?, view: Button) {
        view.apply {
            val context = this@VersionsActivity
            text = link?.name
            visibility = if (link != null) VISIBLE else GONE
            setOnClickListener(if (link?.uri != null) {
                View.OnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, link.uri.toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (!intent.isSafe(context)) {
                        val text = buildSpannedString {
                            append(getString(R.string.versions_toast_link_error))
                            append("\n")
                            bold {
                                italic {
                                    append("${link.uri}")
                                }
                            }
                        }
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    } else {
                        safeStartActivity(intent)
                    }
                }
            } else null)
            setOnLongClickListener(if (link?.uri != null) {
                View.OnLongClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip = ClipData.newPlainText(packageName, link.uri)
                    Toast.makeText(context, getString(R.string.versions_toast_link_to_clipboard), Toast.LENGTH_SHORT).show()
                    true
                }
            } else null)
        }
    }

    private fun downloadVersion(application: Application, version: Version, force: Boolean) {
        Log.d("APK Download", "downloadVersion(${application.name}, ${version.name}, force=$force)")
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, version.key)
            putString(FirebaseAnalytics.Param.ITEM_NAME, version.name)
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, "apk")
        })

        // Cancel the current active download task if forced
        version.getActiveDownloadTask()?.let {
            if (force) {
                it.cancel()
            } else {
                Log.d("APK Download", "Version already downloading")
                return
            }
        }

        val apkRef = version.apkRef
        val apkUrl = version.apkUrl
        when {
            !apkRef.isNullOrBlank() && apkRef != null -> {
                val apkFile = ApkFileProvider.apkFile(applicationContext, version)
                // Install apk if it's already downloaded
                if (apkFile.exists() && apkFile.length() > 0 && !force) {
                    installVersion(version, apkFile)
                    return
                }
                // Make sure to delete the dst file
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                version.updateStatus(DOWNLOADING, progress = 0)
                versionAdapter?.updateVersionProgress(version)

                val tmpFile = ApkFileProvider.tempApkFile(applicationContext, version)

                Log.d("APK Download", "Files: tmp:${tmpFile.path}, ${tmpFile.exists()} apk:${apkFile.path}, ${apkFile.exists()}")

                Firebase.storage.getReference(apkRef).getFile(tmpFile)
                        .addOnProgressListener(DownloadProgressListener(this, version))
                        .addOnCompleteListener(DownloadCompleteListener(this, version, primary = true))
            }
            !apkUrl.isNullOrBlank() -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                if (intent.isSafe(this)) {
                    version.updateStatus(OPENING)
                    versionAdapter?.updateVersionProgress(version)
                    val requestCode = create(VersionRequest.Action.OPEN, version)
                    safeStartActivityForResult(intent, requestCode)
                } else {
                    Toast.makeText(this, R.string.versions_toast_application_open_error, Toast.LENGTH_SHORT).show()
                }
            }
            else -> Log.e("APK Download", "No apk file to download")
        }
    }

    class DownloadProgressListener(
            activity: VersionsActivity,
            val version: Version
    ) : OnProgressListener<FileDownloadTask.TaskSnapshot> {

        private val activityReference: WeakReference<VersionsActivity> = WeakReference(activity)

        override fun onProgress(task: FileDownloadTask.TaskSnapshot?) {
            Log.d("DownloadProgress", "onProgress(${task?.bytesTransferred} / ${task?.totalByteCount})")
            update(task, version, activityReference.get()?.versionAdapter)
        }

        companion object {
            fun update(task: FileDownloadTask.TaskSnapshot?, version: Version, versionAdapter: VersionAdapter?) {
                val bytesTransferred = task?.bytesTransferred
                val totalByteCount = task?.totalByteCount
                if (bytesTransferred is Long && totalByteCount is Long && totalByteCount > 0L) {
                    version.updateStatus(DOWNLOADING, progress = ((100F * bytesTransferred) / totalByteCount).toInt())
                } else {
                    version.updateStatus(DOWNLOADING, progress = 0)
                }
                versionAdapter?.updateVersionProgress(version)
            }
        }

    }

    class DownloadCompleteListener(
            activity: VersionsActivity,
            val version: Version,
            private val primary: Boolean
    ) : OnCompleteListener<FileDownloadTask.TaskSnapshot> {

        private val appContext: Context = activity.applicationContext
        private val activityReference: WeakReference<VersionsActivity> = WeakReference(activity)

        override fun onComplete(task: Task<FileDownloadTask.TaskSnapshot>) {
            if (task.isSuccessful) {
                Log.d("DownloadComplete", "onSuccess(${version.key}, ${version.name})")
                val tmpFile = ApkFileProvider.tempApkFile(appContext, version)
                val apkFile = ApkFileProvider.apkFile(appContext, version)
                // Only the primary listener should move the file to avoid corrupted file
                if (primary) {
                    apkFile.delete()
                    if (!tmpFile.renameTo(apkFile)) {
                        Log.e("DownloadComplete", "Unable to rename: ${tmpFile.path} -> ${apkFile.path}")
                    } else {
                        Log.d("DownloadComplete", "Apk file ready: ${tmpFile.path} -> ${apkFile.path}")
                    }
                }
                activityReference.get()?.let {
                    if (it.lifecycle.currentState.isAtLeast(STARTED)) {
                        it.installVersion(version, apkFile)
                    }
                }
            } else {
                val exception = task.exception
                Log.e("DownloadComplete", "onFailure($exception) code:${exception?.message} cancelled:${task.isCanceled}")
                version.updateStatus(DEFAULT)
                activityReference.get()?.let {
                    it.versionAdapter?.updateVersionProgress(version)
                    if (exception != null) {
                        Toast.makeText(it, "${exception.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    }

    @SuppressLint("SetWorldReadable")
    private fun installVersion(version: Version, file: File) {
        version.updateStatus(INSTALLING)
        versionAdapter?.updateVersionProgress(version)
        val install = Intent().apply {
            action = Intent.ACTION_INSTALL_PACKAGE
            data = if (Utils.isAtLeast(N)) {
                ApkFileProvider.uri(file, applicationContext)
            } else {
                Uri.fromFile(file.apply { setReadable(true, false) })
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            @Suppress("DEPRECATION")
            putExtra(Intent.EXTRA_ALLOW_REPLACE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, applicationInfo.packageName)
        }
        val requestCode = VersionRequest.create(VersionRequest.Action.INSTALL, version)
        safeStartActivityForResult(install, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d("Version", "onActivityResult($requestCode, $resultCode, $data)")
        VersionRequest.extract(requestCode)?.let {
            val version = it.version
            when (it.action) {
                VersionRequest.Action.UNINSTALL -> if (!isApplicationInstalled(application)) updateApplication(application)
                VersionRequest.Action.INSTALL -> {
                    when (resultCode) {
                        AppCompatActivity.RESULT_OK -> Toast.makeText(this, spannedBoldString(getString(R.string.versions_toast_application_installed), Color.GREEN), LENGTH_LONG).show()
                        else -> Toast.makeText(this, spannedBoldString(getString(R.string.versions_toast_application_not_installed), Color.RED), LENGTH_LONG).show()
                    }
                    if (version?.status == INSTALLING) {
                        version.updateStatus(DEFAULT)
                        versionAdapter?.updateVersionProgress(version)
                    }
                }
                VersionRequest.Action.OPEN -> {
                    if (version?.status == OPENING) {
                        version.updateStatus(DEFAULT)
                        versionAdapter?.updateVersionProgress(version)
                    }
                }
            }
        } ?: super.onActivityResult(requestCode, resultCode, data)
    }

    private fun spannedBoldString(string: String, color: Int): SpannedString {
        return buildSpannedString {
            bold {
                color(color) {
                    append(string)
                }
            }
        }
    }

    private fun isApplicationInstalled(application: Application?): Boolean {
        return Utils.isApplicationInstalled(this, application?.packageName.orEmpty())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_versions, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val applicationInstalled = isApplicationInstalled(application)
        menu?.findItem(R.id.menu_action_info)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_action_start)?.isVisible = applicationInstalled && Utils.getLaunchIntent(applicationContext, packageName).isSafe(this) == true
        menu?.findItem(R.id.menu_action_stop)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_action_uninstall)?.isVisible = applicationInstalled
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> supportFinishAfterTransition()
            R.id.menu_action_start -> application?.packageName?.let { safeStartActivity(Utils.getLaunchIntent(applicationContext, it)) }
            R.id.menu_action_stop -> application?.packageName?.let { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(it) }
            R.id.menu_action_info -> application?.packageName?.let { safeStartActivity(Utils.getDetailsIntent(it)) }
            R.id.menu_action_uninstall -> application?.packageName?.let { safeStartActivityForResult(Utils.getDeleteIntent(it), create(VersionRequest.Action.UNINSTALL)) }
            R.id.menu_action_store -> application?.packageName?.let { safeStartActivity(Utils.getMarketIntent(it)) }
            R.id.menu_action_notification_settings -> {
                application?.let { safeStartActivity(Utils.notificationSettingsIntent(this, Notifications.newVersionsNotificationChannelId(this, it))) }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

}
