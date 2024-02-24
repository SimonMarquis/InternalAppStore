package fr.smarquis.appstore

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.LOLLIPOP
import android.os.Build.VERSION_CODES.N
import android.os.Bundle
import android.os.Process
import android.transition.Transition
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.italic
import androidx.core.util.Pair
import androidx.core.view.ViewCompat
import androidx.core.view.setMargins
import androidx.core.widget.ContentLoadingProgressBar
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.EmojiCompat.LOAD_STATE_SUCCEEDED
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.OnProgressListener
import fr.smarquis.appstore.Version.*
import fr.smarquis.appstore.Version.Status.DEFAULT
import fr.smarquis.appstore.Version.Status.DOWNLOADING
import fr.smarquis.appstore.Version.Status.INSTALLING
import fr.smarquis.appstore.Version.Status.OPENING
import fr.smarquis.appstore.VersionRequest.Action.INSTALL
import fr.smarquis.appstore.VersionRequest.Action.OPEN
import fr.smarquis.appstore.VersionRequest.Action.UNINSTALL
import fr.smarquis.appstore.VersionRequest.Companion.create
import fr.smarquis.appstore.databinding.ActivityVersionsBinding
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.hypot
import com.google.android.material.R as MaterialR

class VersionsActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    companion object {

        private const val TAG = "AppStore"

        private val EXTRAS = VersionsActivity::class.java.`package`
        private val EXTRA_APPLICATION = "$EXTRAS.EXTRA_APPLICATION"
        private val EXTRA_HIGHLIGHT_VERSION_KEY = "$EXTRAS.EXTRA_HIGHLIGHT_VERSION_KEY"
        private val EXTRA_APPLICATION_UNKNOWN = "$EXTRAS.EXTRA_APPLICATION_UNKNOWN"
        private val EXTRA_SHARED_ELEMENT_TRANSITION = "$EXTRAS.EXTRA_SHARED_ELEMENT_TRANSITION"

        fun start(activity: AppCompatActivity, application: Application, highlightVersionKey: String? = null, unknown: Boolean = false, sharedElement: Pair<View, String>? = null) {
            val intent = intent(activity, application, highlightVersionKey, unknown)
            if (sharedElement == null) {
                intent.putExtra(EXTRA_SHARED_ELEMENT_TRANSITION, false)
                activity.startActivity(intent)
            } else {
                intent.putExtra(EXTRA_SHARED_ELEMENT_TRANSITION, true)
                activity.startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(activity, sharedElement).toBundle())
            }
        }

        fun intent(context: Context, application: Application, highlightVersionKey: String? = null, unknown: Boolean = false): Intent {
            return Intent(context, VersionsActivity::class.java).apply {
                putExtra(EXTRA_APPLICATION, application)
                putExtra(EXTRA_APPLICATION_UNKNOWN, unknown)
                highlightVersionKey?.let {
                    putExtra(EXTRA_HIGHLIGHT_VERSION_KEY, it)
                }
            }
        }
    }

    private lateinit var binding: ActivityVersionsBinding
    private var firebaseApplicationDatabaseRef: DatabaseReference? = null
    private var versionAdapter: VersionAdapter? = null
    private var application: Application? = null
    private val shortcuts: Shortcuts by lazy { Shortcuts.instance(this) }

    private lateinit var constraintLayout: ConstraintLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar
    private lateinit var links: HorizontalScrollView
    private lateinit var fab: FloatingActionButton
    private lateinit var searchView: SearchView

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
                    val message = if (intent.getBooleanExtra(EXTRA_APPLICATION_UNKNOWN, false)) R.string.versions_toast_application_not_found else R.string.versions_toast_application_removed
                    Toast.makeText(applicationContext, message, LENGTH_SHORT).show()
                    supportFinishAfterTransition()
                } else {
                    updateApplication(update)
                    if (reportShortcutUsage) {
                        shortcuts.use(update)
                        reportShortcutUsage = false
                    }
                }
            }
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
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val firstCompletelyVisibleItemPositionBefore = layoutManager.findFirstCompletelyVisibleItemPosition()
                if (ViewCompat.isLaidOut(constraintLayout)) TransitionManager.beginDelayedTransition(constraintLayout as ViewGroup)
                val recyclerViewTopPadding = if (visible) baselineGrid else 0
                recyclerView.setPadding(0, recyclerViewTopPadding, 0, 0)
                val firstCompletelyVisibleItemPositionAfter = layoutManager.findFirstCompletelyVisibleItemPosition()
                // Force re-scroll to first completely visible item when padding is added
                if (visible && firstCompletelyVisibleItemPositionBefore == 0 && firstCompletelyVisibleItemPositionAfter != firstCompletelyVisibleItemPositionBefore) {
                    layoutManager.scrollToPosition(firstCompletelyVisibleItemPositionBefore)
                }
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
        if (application?.key == null) return finish()
        reportShortcutUsage = savedInstanceState == null

        setContentView(ActivityVersionsBinding.inflate(layoutInflater).also { binding = it }.root)

        excludeTransitionTargets()
        initUi(savedInstanceState, application)
        initCircularReveal(savedInstanceState)
        registerListeners(application)
        updateApplication(application)
        PackageIntentFilter.init(this) { _: String, packageName: String -> if (packageName == application.packageName) updateApplication(application) }
    }

    private fun excludeTransitionTargets() {
        if (SDK_INT < LOLLIPOP) {
            return
        }
        val transitions = with(window) { listOfNotNull(enterTransition, exitTransition, sharedElementEnterTransition, sharedElementExitTransition) }
        transitions.forEach {
            it.excludeTarget(android.R.id.statusBarBackground, true)
            it.excludeTarget(android.R.id.navigationBarBackground, true)
        }
    }

    override fun onStart() {
        super.onStart()
        application?.key?.let {
            Notifications.cancel(this, it, R.id.notification_new_application_id)
            Notifications.cancel(this, it, R.id.notification_new_version_id)
        }
    }

    override fun onRestart() {
        super.onRestart()
        updateApplication(application)
        versionAdapter?.notifyDataSetChanged()
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
        hasRegisteredListeners = true
    }

    private fun unregisterListeners() {
        if (!hasRegisteredListeners) {
            return
        }
        scrollToHighlightedVersionDataObserver?.let { versionAdapter?.unregisterAdapterDataObserver(it) }
        firebaseApplicationDatabaseRef?.removeEventListener(applicationValueEventListener)
        hasRegisteredListeners = false
    }

    private fun initUi(savedInstanceState: Bundle?, application: Application) {
        ViewCompat.setTransitionName(binding.includeHeader.headerIcon, if (savedInstanceState == null) application.imageTransitionName() else null)
        constraintLayout = binding.constraintLayout
        recyclerView = binding.versions
        contentLoadingProgressBar = binding.progressVersions
        contentLoadingProgressBar.show()

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            elevation = 0F
        }

        versionAdapter = VersionAdapter(
            lifecycleOwner = this,
            query = Firebase.database.versions(application.key.orEmpty()),
            callback = object : VersionAdapter.Callback {

                override fun onDataChanged() = contentLoadingProgressBar.hide()

                override fun onChildAdded(version: Version) = refreshVersionProperties(version)

                override fun onItemClicked(version: Version, versionViewHolder: VersionViewHolder) {
                    when {
                        version.hasApkUrl() -> openVersion(version)
                        version.hasApkRef() -> downloadVersion(version, false) { installVersion(it) }
                    }
                }

                override fun onItemLongClicked(version: Version, versionViewHolder: VersionViewHolder): Boolean = when {
                    version.hasApkUrl() || version.hasApkRef() -> popupMenu(version, versionViewHolder)
                    else -> false
                }

                private fun popupMenu(version: Version, versionViewHolder: VersionViewHolder): Boolean {
                    val hasApkUrl = version.hasApkUrl()
                    val hasApkRef = version.hasApkRef()
                    val isDownloading = hasApkRef && version.getActiveDownloadTask()?.isComplete == false
                    PopupMenu(this@VersionsActivity, versionViewHolder.anchor, Gravity.END).apply {
                        menuInflater.inflate(R.menu.menu_version, menu)
                        menu.findItem(R.id.menu_version_cancel).isVisible = hasApkRef && isDownloading
                        menu.findItem(R.id.menu_version_download).isVisible = hasApkUrl || hasApkRef && !isDownloading && !version.apkFileAvailable
                        menu.findItem(R.id.menu_version_install).isVisible = hasApkRef && !isDownloading
                        menu.findItem(R.id.menu_version_share).isVisible = hasApkUrl || hasApkRef && !isDownloading
                        menu.findItem(R.id.menu_version_delete).isVisible = hasApkRef && !isDownloading && version.apkFileAvailable
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                R.id.menu_version_cancel -> version.getActiveDownloadTask()?.cancel()
                                R.id.menu_version_download -> if (hasApkUrl) openVersion(version) else downloadVersion(version, true)
                                R.id.menu_version_install -> downloadVersion(version) { installVersion(it) }
                                R.id.menu_version_share -> if (hasApkUrl) shareVersion(version) else downloadVersion(version) { shareVersion(it) }
                                R.id.menu_version_delete -> deleteVersion(version)
                            }
                            true
                        }
                        show()
                        // Dismiss popup if download completes
                        if (isDownloading) {
                            version.getActiveDownloadTask()?.addOnCompleteListener(this@VersionsActivity) { dismiss() }
                        }
                    }
                    return true
                }
            },
        ).apply {
            // Scroll to the highlighted version
            val highlightVersionKey = intent.extras?.getString(EXTRA_HIGHLIGHT_VERSION_KEY)
            intent.extras?.remove(EXTRA_HIGHLIGHT_VERSION_KEY)
            if (!highlightVersionKey.isNullOrBlank()) {
                // provide adapter with the version key to highlight
                highlightVersion(highlightVersionKey)
                // detect highlighted version insertion and scroll to position
                scrollToHighlightedVersionDataObserver = ScrollToHighlightedVersionDataObserver(recyclerView, this, highlightVersionKey).apply {
                    registerAdapterDataObserver(this)
                }
            }
        }

        recyclerView.apply {
            val orientation = RecyclerView.VERTICAL
            layoutManager = LinearLayoutManager(this@VersionsActivity, orientation, false)
            adapter = versionAdapter
            setHasFixedSize(true)
            addItemDecoration(DividerItemDecoration(context, orientation))
            isScrollbarFadingEnabled = DEBUG_SCREENSHOT_STATUS == null
        }

        links = binding.includeHeader.header
        fab = binding.fab
        fab.setOnClickListener { startApplication() }
    }

    private var scrollToHighlightedVersionDataObserver: ScrollToHighlightedVersionDataObserver? = null

    class ScrollToHighlightedVersionDataObserver(
        private val recycler: RecyclerView,
        private val adapter: VersionAdapter,
        private val key: String,
    ) : RecyclerView.AdapterDataObserver() {

        private var found: Boolean = false

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (found) return
            for (position in positionStart until positionStart + itemCount) {
                val version = adapter.getItem(position)
                if (key != version.key) continue
                found = true
                recycler.post {
                    val positionOf = adapter.positionOf(version)
                    if (positionOf != NO_POSITION) {
                        recycler.scrollToPosition(positionOf)
                    }
                }
                return
            }
        }
    }

    @TargetApi(LOLLIPOP)
    private fun initCircularReveal(savedInstanceState: Bundle?) {
        if (SDK_INT < LOLLIPOP) return
        // SharedElementTransition is not run when Activity is recreated during configuration change
        if (savedInstanceState != null) return
        // SharedElementTransition is not run when Activity is started from a Notification
        if (intent?.getBooleanExtra(EXTRA_SHARED_ELEMENT_TRANSITION, false) != true) return
        // assuming sharedElementEnterTransition and sharedElementReturnTransition are the same
        window.sharedElementEnterTransition?.let { enterTransition ->
            isCircularRevealPending = true
            val header = binding.includeHeader.root.apply {
                // Initially visible, laid out for shared element transition, then invisible
                post { visibility = View.INVISIBLE }
            }
            val center = binding.includeHeader.centerIcon
            enterTransition.addListener(
                object : Transition.TransitionListener {

                    private var activityTransitionFlag: Boolean = false

                    override fun onTransitionStart(p0: Transition?) {
                        val radiusInvisible = 0F
                        val radiusVisible = hypot(header.width.toDouble() - center.left, header.height.toDouble() - center.top).toFloat()
                        val start = if (activityTransitionFlag) radiusVisible else radiusInvisible
                        val end = if (activityTransitionFlag) radiusInvisible else radiusVisible
                        ViewAnimationUtils.createCircularReveal(header, center.left, center.top, start, end).apply {
                            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                            interpolator = if (activityTransitionFlag) DecelerateInterpolator(2F) as TimeInterpolator else AccelerateInterpolator(2F) as TimeInterpolator
                            addListener(
                                onStart = { header.visibility = VISIBLE },
                                onEnd = {
                                    isCircularRevealPending = false
                                    application?.let { app -> updateFab(app) }
                                },
                            )
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
                },
            )
        }
    }

    private fun refreshVersionProperties(version: Version) {
        // Restore active downloads and check for file size and apk file availability
        version.getActiveDownloadTask()?.apply { DownloadProgressListener.update(snapshot, version, versionAdapter) }
            ?.addOnProgressListener(DownloadProgressListener(this, version))
            ?.addOnCompleteListener(DownloadCompleteListener(this, version))
        if (!version.hasApkRef()) return

        val context = applicationContext
        val fileSizeTask = if (version.apkSize == null) Firebase.storage.getReference(version.apkRef!!).metadata.addOnSuccessListener(this) { version.updateApkSize(it.sizeBytes) } else null
        val fileAvailabilityTask = Tasks.call(executor) {
            ApkFileProvider.apkFile(context, version).let {
                version.apkFileAvailable = it.exists() && it.length() > 0
            }
        }
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
        application.loadImageInto(binding.includeHeader.headerIcon, Glide.with(this))
    }

    private fun updateAppDescription(application: Application) {
        val textView: TextView = binding.includeHeader.headerDescription
        val textView2: TextView = binding.includeHeader.headerInstalledInfo
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
        updateAppLink(application.link1, binding.includeHeader.link1)
        updateAppLink(application.link2, binding.includeHeader.link2)
        updateAppLink(application.link3, binding.includeHeader.link3)
        updateAppLink(application.link4, binding.includeHeader.link4)
        updateAppLink(application.link5, binding.includeHeader.link5)
        links.apply {
            visibility = if (application.link1 == null && application.link2 == null && application.link3 == null && application.link4 == null && application.link5 == null) GONE else VISIBLE
        }
    }

    private fun updateAppLink(link: Link?, view: Button) {
        view.apply {
            text = link?.name
            visibility = if (link != null) VISIBLE else GONE
            setOnClickListener(
                if (link?.uri != null) {
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
                } else null,
            )
            setOnLongClickListener(
                if (link?.uri != null) {
                    View.OnLongClickListener {
                        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(packageName, link.uri))
                        Toast.makeText(this@VersionsActivity, getString(R.string.versions_toast_link_to_clipboard), LENGTH_SHORT).show()
                        true
                    }
                } else null,
            )
        }
    }

    private fun updateFab(application: Application) {
        if (isApplicationInstalled(application) && Utils.getLaunchIntent(applicationContext, packageName)?.isSafe(this) == true) {
            if (isCircularRevealPending) {
                // Force setting the RecyclerView top padding before items are added
                // This will prevent the RecyclerView to appear scrolled down by the amount of top padding
                recyclerView.setPadding(0, baselineGrid, 0, 0)
            } else {
                fab.show(fabVisibilityChangedListener)
            }
        } else {
            fab.hide(fabVisibilityChangedListener)
        }
    }

    private fun startApplication() {
        application?.packageName?.let { safeStartActivity(Utils.getLaunchIntent(applicationContext, it) ?: return) }
    }

    private fun openApplicationInfo() {
        application?.packageName?.let { safeStartActivity(Utils.getDetailsIntent(it)) }
    }

    private fun openApplicationOnMarket() {
        application?.packageName?.let { safeStartActivity(Utils.getMarketIntent(it)) }
    }

    private fun openApplicationNotificationSettings() {
        application?.let { safeStartActivity(Utils.notificationSettingsIntent(this, Notifications.newVersionsNotificationChannelId(this, it))) }
    }

    private fun createApplicationShortcut() {
        application?.let { shortcuts.request(it) }
    }

    private fun killApplicationProcess() {
        application?.packageName?.let {
            if (it == packageName) Process.killProcess(Process.myPid())
            else (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(it)
        }
    }

    private fun uninstallApplication() {
        application?.packageName?.let { safeStartActivityForResult(Utils.getDeleteIntent(it), create(UNINSTALL)) }
    }

    private fun downloadVersion(version: Version, force: Boolean = false, continuation: ((version: Version) -> Unit)? = null) {
        if (!version.hasApkRef()) return

        // Cancel the current active download task if forced
        version.getActiveDownloadTask()?.apply {
            if (!force) {
                Log.d("APK Download", "Version already downloading")
                return
            }
            cancel()
        }

        val apkFile = ApkFileProvider.apkFile(applicationContext, version)
        // If file is already available, execute continuation block
        if (apkFile.exists() && apkFile.length() > 0 && !force) {
            continuation?.invoke(version)
            return
        }

        // Make sure to delete the dst file
        if (apkFile.exists()) {
            apkFile.delete()
        }

        version.updateStatus(DOWNLOADING, progress = 0)
        versionAdapter?.updateVersionProgress(version)

        Firebase.storage.getReference(version.apkRef!!).getFile(ApkFileProvider.tempApkFile(applicationContext, version))
            .addOnProgressListener(DownloadProgressListener(this, version))
            .addOnCompleteListener(DownloadCompleteListener(this, version, primary = true, continuation = continuation))
            .addOnCompleteListener { Firebase.database.analytics().downloaded(application, version) }
    }

    private fun openVersion(version: Version): Boolean {
        if (!version.hasApkUrl()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(version.apkUrl))
        return if (intent.isSafe(this)) {
            Firebase.logSelectedContent(application, version)
            version.updateStatus(OPENING)
            versionAdapter?.updateVersionProgress(version)
            val requestCode = create(OPEN, version)
            safeStartActivityForResult(intent, requestCode)
        } else {
            Toast.makeText(this, R.string.versions_toast_application_open_error, LENGTH_SHORT).show()
            false
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun installVersion(version: Version) {
        if (!version.hasApkRef()) return
        if (!version.apkFileAvailable) return
        val apkFile = ApkFileProvider.apkFile(applicationContext, version)
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return
        }
        version.updateStatus(INSTALLING)
        versionAdapter?.updateVersionProgress(version)
        @Suppress("DEPRECATION") val install = Intent().apply {
            action = Intent.ACTION_INSTALL_PACKAGE
            data = if (Utils.isAtLeast(N)) {
                ApkFileProvider.uri(apkFile, applicationContext)
            } else {
                Uri.fromFile(apkFile.apply { setReadable(true, false) })
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            @Suppress("DEPRECATION")
            putExtra(Intent.EXTRA_ALLOW_REPLACE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, applicationInfo.packageName)
        }
        val requestCode = create(INSTALL, version)
        safeStartActivityForResult(install, requestCode)
    }

    private fun shareVersion(version: Version) {
        when {
            version.hasApkUrl() -> {
                safeStartActivity(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${application?.name} ${version.name}")
                        putExtra(Intent.EXTRA_TEXT, version.apkUrl)
                    },
                )
                return
            }

            version.hasApkRef() && version.apkFileAvailable -> {
                val apkFile = ApkFileProvider.apkFile(applicationContext, version)
                if (!apkFile.exists() || apkFile.length() <= 0) {
                    return
                }
                safeStartActivity(ApkFileProvider.shareIntent(this, application ?: return, version))
            }
        }
    }

    private fun deleteVersion(version: Version) {
        if (!version.hasApkRef()) return
        version.getActiveDownloadTask()?.cancel()
        ApkFileProvider.delete(applicationContext, version) {
            refreshVersionProperties(version)
        }
    }

    class DownloadProgressListener(
        activity: VersionsActivity,
        val version: Version,
    ) : OnProgressListener<FileDownloadTask.TaskSnapshot> {

        private val activityReference: WeakReference<VersionsActivity> = WeakReference(activity)

        override fun onProgress(task: FileDownloadTask.TaskSnapshot) {
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
        val primary: Boolean = false,
        private val continuation: ((version: Version) -> Unit)? = null,
    ) : OnCompleteListener<FileDownloadTask.TaskSnapshot> {

        private val appContext: Context = activity.applicationContext
        private val activityReference: WeakReference<VersionsActivity> = WeakReference(activity)

        override fun onComplete(task: Task<FileDownloadTask.TaskSnapshot>) {
            version.updateStatus(DEFAULT)
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
                    // Block should be run only when Activity is at least in STARTED state
                    if (it.lifecycle.currentState.isAtLeast(STARTED)) {
                        it.versionAdapter?.updateVersionProgress(version)
                        continuation?.invoke(version)
                    }
                }
            } else {
                val exception = task.exception
                Log.e("DownloadComplete", "onFailure($exception) code:${exception?.message} cancelled:${task.isCanceled}")
                activityReference.get()?.let {
                    it.versionAdapter?.updateVersionProgress(version)
                    if (exception != null) {
                        Toast.makeText(it, "${exception.message}", LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        VersionRequest.extract(requestCode)?.let { request ->
            val version = request.version
            when (request.action) {
                UNINSTALL -> if (!isApplicationInstalled(application)) updateApplication(application)
                INSTALL -> {
                    when {
                        resultCode == RESULT_OK -> {
                            Firebase.database.analytics().installed(application, version)
                            version?.let { Firebase.logSelectedContent(application, it) }
                            Snackbar.make(constraintLayout, R.string.versions_toast_application_installed, Snackbar.LENGTH_LONG)
                                .setAction(R.string.versions_toast_application_installed_action) { startApplication() }.customize().show()
                        }

                        resultCode == RESULT_CANCELED || !isApplicationInstalled(application) -> Snackbar.make(
                            constraintLayout,
                            R.string.versions_toast_application_not_installed,
                            Snackbar.LENGTH_LONG,
                        ).customize().show()

                        else -> Snackbar.make(constraintLayout, R.string.versions_toast_application_not_installed_uninstall_first, Snackbar.LENGTH_LONG)
                            .setAction(R.string.versions_toast_application_not_installed_uninstall_first_action) { uninstallApplication() }.customize().show()
                    }
                    if (version?.status == INSTALLING) {
                        version.updateStatus(DEFAULT)
                        versionAdapter?.updateVersionProgress(version)
                    }
                }

                OPEN -> {
                    if (version?.status == OPENING) {
                        version.updateStatus(DEFAULT)
                        versionAdapter?.updateVersionProgress(version)
                    }
                }
            }
        } ?: super.onActivityResult(requestCode, resultCode, data)
    }

    @SuppressLint("PrivateResource")
    fun Snackbar.customize(): Snackbar {
        with(view) {
            ViewCompat.setElevation(this, context.resources.getDimension(MaterialR.dimen.design_snackbar_elevation))
            (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(context.resources.getDimensionPixelSize(MaterialR.dimen.mtrl_snackbar_margin))
            setBackgroundResource(MaterialR.drawable.design_snackbar_background)
            findViewById<TextView>(MaterialR.id.snackbar_text)?.maxLines = 2
        }
        return this
    }

    private fun isApplicationInstalled(application: Application?): Boolean {
        return Utils.isApplicationInstalled(this, application?.packageName.orEmpty())
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        versionAdapter?.filter(query)
        searchView.clearFocus()
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            versionAdapter?.filter(query)
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_versions, menu)
        val searchItem = menu.findItem(R.id.menu_versions_search)
        searchView = searchItem.actionView as SearchView
        searchView.findViewById<SearchView.SearchAutoComplete>(MaterialR.id.search_src_text).setTextColor(ContextCompat.getColor(this, R.color.colorAccent))
        searchView.setOnQueryTextListener(this)
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus && searchView.query.isNullOrBlank()) {
                searchItem.collapseActionView()
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val applicationInstalled = isApplicationInstalled(application)
        menu?.findItem(R.id.menu_versions_info)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_versions_stop)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_versions_uninstall)?.isVisible = applicationInstalled
        menu?.findItem(R.id.menu_versions_create_shortcut)?.isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(this)
        (menu?.findItem(R.id.menu_versions_search)?.actionView as SearchView).setQuery(versionAdapter?.filter(), false)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> if (isTaskRoot) return super.onOptionsItemSelected(item) else supportFinishAfterTransition()
            R.id.menu_versions_stop -> killApplicationProcess()
            R.id.menu_versions_info -> openApplicationInfo()
            R.id.menu_versions_uninstall -> uninstallApplication()
            R.id.menu_versions_store -> openApplicationOnMarket()
            R.id.menu_versions_notification_settings -> openApplicationNotificationSettings()
            R.id.menu_versions_create_shortcut -> createApplicationShortcut()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

}
