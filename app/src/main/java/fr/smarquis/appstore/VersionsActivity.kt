package fr.smarquis.appstore

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.LOLLIPOP
import android.os.Build.VERSION_CODES.N
import android.os.Bundle
import android.os.Process
import android.text.SpannedString
import android.transition.Transition
import android.util.Log
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.LENGTH_SHORT
import androidx.annotation.Px
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.italic
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.EmojiCompat.LOAD_STATE_SUCCEEDED
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class VersionsActivity : AppCompatActivity() {

    companion object {

        private const val TAG = "AppStore"

        private const val EXTRA_APPLICATION = "EXTRA_APPLICATION"
        private const val EXTRA_HIGHLIGHT_VERSION_KEY = "EXTRA_HIGHLIGHT_VERSION_KEY"
        private const val EXTRA_SHARED_ELEMENT_TRANSITION = "EXTRA_SHARED_ELEMENT_TRANSITION"

        fun start(context: Context, application: Application) {
            context.startActivity(intent(context, application))
        }

        fun start(activity: AppCompatActivity, application: Application, vararg sharedElement: Pair<View, String>) {
            val bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, *sharedElement).toBundle()
            val intent = intent(activity, application).apply { putExtra(EXTRA_SHARED_ELEMENT_TRANSITION, true) }
            activity.startActivity(intent, bundle)
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
    private val shortcuts: Shortcuts by lazy { Shortcuts.instance(this) }

    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar
    private lateinit var links: HorizontalScrollView
    private lateinit var fab: FloatingActionButton

    @delegate:Px
    @delegate:SuppressLint("PrivateResource")
    private val fabSizeMini by lazy { resources.getDimensionPixelSize(com.google.android.material.R.dimen.design_fab_size_mini) }
    @delegate:Px
    private val baselineGrid by lazy { resources.getDimensionPixelSize(R.dimen.material_baseline_grid_1x) }

    private val applicationValueEventListener: ValueEventListener = object : ValueEventListener {
        override fun onCancelled(error: DatabaseError) {
            error.let {
                Log.w(TAG, "Error fetching application ${application?.key}\n${it.message}\n${it.details}", error.toException())
                Toast.makeText(this@VersionsActivity, it.message, LENGTH_LONG).show()
                supportFinishAfterTransition()
            }
        }

        override fun onDataChange(snapshot: DataSnapshot) {
            Application.parse(snapshot).let { update ->
                if (update == null) {
                    Toast.makeText(applicationContext, R.string.versions_toast_application_removed, LENGTH_SHORT).show()
                    application?.let {
                        Notifications.deleteNewVersionsNotificationChannel(this@VersionsActivity, it)
                        shortcuts.remove(it)
                    }
                    supportFinishAfterTransition()
                } else {
                    Notifications.createOrUpdateNewVersionsNotificationChannel(this@VersionsActivity, update)
                    updateApplication(update)
                    if (reportShortcutUsage) {
                        shortcuts.use(update)
                        reportShortcutUsage = false
                    }
                }
            }
        }
    }

    private val dataObserver: RecyclerView.AdapterDataObserver = object : RecyclerView.AdapterDataObserver() {

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            contentLoadingProgressBar.hide()

            for (position in positionStart until positionStart + itemCount) {
                refreshVersionProperties(versionAdapter?.getItem(position) ?: continue)
            }
        }
    }

    private val packageIntentFilterReceiver = PackageIntentFilter.receiver { _: String, packageName: String ->
        if (packageName == application?.packageName) {
            updateApplication(application)
        }
    }

    private val executor by lazy { Executors.newSingleThreadExecutor() }

    private val fabVisibilityChangedListener by lazy {
        object : FloatingActionButton.OnVisibilityChangedListener() {
            override fun onShown(view: FloatingActionButton?) {
                updatePadding(true)
            }

            override fun onHidden(view: FloatingActionButton?) {
                updatePadding(false)
            }

            private fun updatePadding(visible: Boolean) {
                if (ViewCompat.isLaidOut(constraintLayout)) TransitionManager.beginDelayedTransition(constraintLayout as ViewGroup)
                val recyclerViewTopPadding = if (visible) baselineGrid else 0
                recyclerView.setPadding(0, recyclerViewTopPadding, 0, 0)
                val linksRightPadding = if (visible) fabSizeMini else 0
                links.setPadding(0, 0, linksRightPadding, 0)
            }
        }
    }

    private var isCircularRevealPending = false

    private var hasRegisteredListeners = false

    /**
     * Used to report shortcuts only once (prevent configuration change, data change, etc.)
     */
    private var reportShortcutUsage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AppStore)
        super.onCreate(savedInstanceState)
        val application = intent?.let {
            it.extras?.getParcelable(EXTRA_APPLICATION) ?: shortcuts.extract(it)
        }
        if (application?.key == null) {
            finish()
            return
        }
        reportShortcutUsage = savedInstanceState == null

        setContentView(R.layout.activity_versions)
        initUi(application)
        initCircularReveal(savedInstanceState)
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
        super.onDestroy()
        unregisterListeners()
    }

    private fun registerListeners(application: Application) {
        if (hasRegisteredListeners) {
            return
        }
        firebaseApplicationDatabaseRef = Firebase.database.application(application.key.orEmpty())
        firebaseApplicationDatabaseRef?.addValueEventListener(applicationValueEventListener)
        versionAdapter?.registerAdapterDataObserver(dataObserver)
        versionAdapter?.startListening()
        PackageIntentFilter.register(this, packageIntentFilterReceiver)
        hasRegisteredListeners = true
    }

    private fun unregisterListeners() {
        if (!hasRegisteredListeners) {
            return
        }
        firebaseApplicationDatabaseRef?.removeEventListener(applicationValueEventListener)
        PackageIntentFilter.unregister(this, packageIntentFilterReceiver)
        versionAdapter?.unregisterAdapterDataObserver(dataObserver)
        versionAdapter?.stopListening()
        hasRegisteredListeners = false
    }

    private fun initUi(application: Application) {
        ViewCompat.setTransitionName(findViewById(R.id.imageView_header_icon), application.imageTransitionName())
        constraintLayout = findViewById(R.id.constraintLayout)
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
                    override fun onItemChanged(version: Version) {
                        refreshVersionProperties(version)
                    }

                    override fun onItemClicked(version: Version, versionViewHolder: VersionViewHolder) {
                        downloadVersion(version, false)
                    }

                    override fun onItemLongClicked(version: Version, versionViewHolder: VersionViewHolder): Boolean {
                        downloadVersion(version, true)
                        return true
                    }
                }
        ).apply {
            setHasStableIds(true)
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
            isScrollbarFadingEnabled = Store.SCREENSHOT_STATUS == null
        }

        links = findViewById(R.id.horizontalScrollView_header)
        fab = findViewById(R.id.floatingActionButton)
        fab.setOnClickListener { _ -> application.packageName?.let { safeStartActivity(Utils.getLaunchIntent(applicationContext, it)) } }
    }

    @TargetApi(LOLLIPOP)
    private fun initCircularReveal(savedInstanceState: Bundle?) {
        if (SDK_INT < LOLLIPOP) {
            return
        }
        if (savedInstanceState != null) {
            // SharedElementTransition is not run when Activity is recreated during configuration change
            return
        }
        if (intent?.getBooleanExtra(EXTRA_SHARED_ELEMENT_TRANSITION, false) != true) {
            // SharedElementTransition is not run when Activity is started from a Notification
            return
        }
        // assuming sharedElementEnterTransition and sharedElementReturnTransition are the same
        window.sharedElementEnterTransition?.let { enterTransition ->
            isCircularRevealPending = true
            val header = findViewById<View>(R.id.includeHeader).apply {
                // Initially visible, laid out for shared element transition, then invisible
                post { visibility = View.INVISIBLE }
            }
            val center = findViewById<View>(R.id.view_center_icon)
            enterTransition.addListener(object : Transition.TransitionListener {

                private var activityTransitionFlag: Boolean = false

                override fun onTransitionStart(p0: Transition?) {
                    val radiusInvisible = 0F
                    val radiusVisible = Math.hypot(header.width.toDouble() - center.left, header.height.toDouble() - center.top).toFloat()
                    val start = if (activityTransitionFlag) radiusVisible else radiusInvisible
                    val end = if (activityTransitionFlag) radiusInvisible else radiusVisible
                    ViewAnimationUtils.createCircularReveal(header, center.left, center.top, start, end).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        interpolator = if (activityTransitionFlag) DecelerateInterpolator(2F) else AccelerateInterpolator(2F)
                        addListener(
                                onStart = { header.visibility = VISIBLE },
                                onEnd = { _ ->
                                    isCircularRevealPending = false
                                    application?.let { updateFab(it) }
                                })
                    }.start()
                    if (activityTransitionFlag) {
                        window.sharedElementEnterTransition?.removeListener(this)
                    } else {
                        activityTransitionFlag = true
                    }
                }

                override fun onTransitionResume(p0: Transition?) {}

                override fun onTransitionPause(p0: Transition?) {}

                override fun onTransitionCancel(p0: Transition?) {}

                override fun onTransitionEnd(p0: Transition?) {}
            })
        }
    }

    private fun refreshVersionProperties(version: Version) {
        // Restore active downloads and check for file size and apk file availability
        version.getActiveDownloadTask()?.apply { DownloadProgressListener.update(snapshot, version, versionAdapter) }
                ?.addOnProgressListener(DownloadProgressListener(this, version))
                ?.addOnCompleteListener(DownloadCompleteListener(this, version, primary = false))
        if (!version.hasApkRef()) return

        val context = applicationContext
        val fileSizeTask = if (version.apkSize == null) Firebase.storage.getReference(version.apkRef!!).metadata.addOnSuccessListener(this) { version.updateApkSize(it.sizeBytes) } else null
        val fileAvailabilityTask = Tasks.call(executor, Callable {
            ApkFileProvider.apkFile(context, version).let {
                version.apkFileAvailable = it.exists() && it.length() > 0
            }
        })
        Tasks.whenAllComplete(listOfNotNull(fileSizeTask, fileAvailabilityTask)).addOnCompleteListener(this) { versionAdapter?.updateVersionProgress(version) }
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
        updateFab(application)
        invalidateOptionsMenu()
        return true
    }

    private fun updateAppTitle(application: Application) {
        supportActionBar?.apply {
            title = with(EmojiCompat.get()) {
                if (loadState == LOAD_STATE_SUCCEEDED) process(application.name.orEmpty()) else application.name
            }
            subtitle = application.packageName
        }
    }

    private fun updateAppIcon(application: Application) {
        application.loadImageInto(findViewById(R.id.imageView_header_icon), Glide.with(this))
    }

    private fun updateAppDescription(application: Application) {
        val textView: TextView = findViewById(R.id.textView_header_description)
        val textView2: TextView = findViewById(R.id.textView_header_installedInfo)
        val appInstalled = Utils.isApplicationInstalled(this, application.packageName.orEmpty())
        textView2.visibility = if (appInstalled) VISIBLE else GONE
        if (appInstalled) {
            val appVersionName = Utils.applicationVersionName(this, application.packageName.orEmpty())
            val appVersionCode = Utils.applicationVersionCode(this, application.packageName.orEmpty())
            val time = Utils.relativeTimeSpan(Utils.applicationLastUpdateTime(this, application.packageName.orEmpty()))
            textView2.text = getString(R.string.versions_header_installed_info, appVersionName, appVersionCode, time)
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
            text = link?.name
            visibility = if (link != null) VISIBLE else GONE
            setOnClickListener(if (link?.uri != null) {
                View.OnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, link.uri.toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (!intent.isSafe(this@VersionsActivity)) {
                        val text = buildSpannedString {
                            append(getString(R.string.versions_toast_link_error))
                            append("\n")
                            bold {
                                italic {
                                    append("${link.uri}")
                                }
                            }
                        }
                        Toast.makeText(this@VersionsActivity, text, LENGTH_SHORT).show()
                    } else {
                        safeStartActivity(intent)
                    }
                }
            } else null)
            setOnLongClickListener(if (link?.uri != null) {
                View.OnLongClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip = ClipData.newPlainText(packageName, link.uri)
                    Toast.makeText(this@VersionsActivity, getString(R.string.versions_toast_link_to_clipboard), LENGTH_SHORT).show()
                    true
                }
            } else null)
        }
    }

    private fun updateFab(application: Application) {
        if (isApplicationInstalled(application) && Utils.getLaunchIntent(applicationContext, packageName).isSafe(this)) {
            if (isCircularRevealPending) {
                // Force setting the RecyclerView top padding before items are added
                // This will prevent the RecyclerView to appear scrolled down by the amount of top padding
                recyclerView.setPadding(0, baselineGrid, 0, 0)
                // Force setting the HorizontalScrollView right padding before the animation ends to avoid flash of text
                links.setPadding(0, 0, fabSizeMini, 0)
            } else {
                fab.show(fabVisibilityChangedListener)
            }
        } else {
            fab.hide(fabVisibilityChangedListener)
        }
    }

    private fun downloadVersion(version: Version, force: Boolean) {
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, Bundle().apply {
            putString(FirebaseAnalytics.Param.ITEM_ID, "${version.name} (${version.key})")
            putString(FirebaseAnalytics.Param.ITEM_NAME, "${application?.packageName} ${version.name} (${version.key})")
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, application?.packageName ?: "unknown")
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

        when {
            version.hasApkRef() -> {
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

                Firebase.storage.getReference(version.apkRef!!).getFile(tmpFile)
                        .addOnProgressListener(DownloadProgressListener(this, version))
                        .addOnCompleteListener(DownloadCompleteListener(this, version, primary = true))
            }
            version.hasApkUrl() -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(version.apkUrl))
                if (intent.isSafe(this)) {
                    version.updateStatus(OPENING)
                    versionAdapter?.updateVersionProgress(version)
                    val requestCode = create(VersionRequest.Action.OPEN, version)
                    safeStartActivityForResult(intent, requestCode)
                } else {
                    Toast.makeText(this, R.string.versions_toast_application_open_error, LENGTH_SHORT).show()
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
                    version.apkFileAvailable = true
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
                        Toast.makeText(it, "${exception.message}", LENGTH_LONG).show()
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
        VersionRequest.extract(requestCode)?.let {
            val version = it.version
            when (it.action) {
                VersionRequest.Action.UNINSTALL -> if (!isApplicationInstalled(application)) updateApplication(application)
                VersionRequest.Action.INSTALL -> {
                    when {
                        resultCode == RESULT_OK -> Toast.makeText(this, spannedBoldString(getString(R.string.versions_toast_application_installed), Color.GREEN), LENGTH_LONG).show()
                        resultCode == RESULT_CANCELED || !isApplicationInstalled(application) -> Toast.makeText(this, spannedBoldString(getString(R.string.versions_toast_application_not_installed), Color.RED), LENGTH_LONG).show()
                        else -> Toast.makeText(this, spannedBoldString(getString(R.string.versions_toast_application_not_installed_uninstall_first), Color.RED), LENGTH_LONG).show()
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
        menu?.findItem(R.id.menu_action_stop)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_action_uninstall)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_action_create_shortcut)?.isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> supportFinishAfterTransition()
            R.id.menu_action_stop -> application?.packageName?.let {
                if (it == packageName) Process.killProcess(Process.myPid())
                else (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(it)
            }
            R.id.menu_action_info -> application?.packageName?.let { safeStartActivity(Utils.getDetailsIntent(it)) }
            R.id.menu_action_uninstall -> application?.packageName?.let { safeStartActivityForResult(Utils.getDeleteIntent(it), create(VersionRequest.Action.UNINSTALL)) }
            R.id.menu_action_store -> application?.packageName?.let { safeStartActivity(Utils.getMarketIntent(it)) }
            R.id.menu_action_notification_settings -> application?.let { safeStartActivity(Utils.notificationSettingsIntent(this, Notifications.newVersionsNotificationChannelId(this, it))) }
            R.id.menu_action_create_shortcut -> application?.let { shortcuts.request(it) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

}
