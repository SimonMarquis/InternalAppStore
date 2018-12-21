package fr.smarquis.appstore

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.AsyncTask
import android.os.Build.VERSION_CODES.KITKAT
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.widget.ContentLoadingProgressBar
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener


class ApplicationsActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    companion object {

        private const val TAG = "AppStore"

        const val REQUEST_CODE_AUTO_SIGN_IN = 1
        const val REQUEST_CODE_MANUAL_SIGN_IN = 2
        const val REQUEST_CODE_NOTIFICATION_SETTINGS = 3

        const val PREFERENCE_NAME_FAVORITES = "applications_favorites"

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ApplicationsActivity::class.java))
        }

    }

    private var applicationAdapter: ApplicationAdapter? = null
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchView: SearchView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AppStore)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_applications)
        initUi()
        PackageIntentFilter.init(this) { _: String, packageName: String -> applicationAdapter?.onPackageChanged(packageName) }
        checkStoreAccess(sendEmailVerification = false, reloadUser = true)
    }

    override fun onStart() {
        super.onStart()
        updateSubtitle()
        invalidateOptionsMenu()
    }

    override fun onRestart() {
        super.onRestart()
        updateSubtitle()
        invalidateOptionsMenu()
        applicationAdapter?.notifyDataSetChanged()
        Firebase.auth.currentUser?.let {
            if (!it.isEmailVerified) {
                checkStoreAccess(sendEmailVerification = false, reloadUser = true)
            }
        }
    }

    private fun initUi() {
        contentLoadingProgressBar = findViewById(R.id.contentLoadingProgressBar_applications)
        contentLoadingProgressBar.show()

        applicationAdapter = ApplicationAdapter(
                lifecycleOwner = this,
                query = Firebase.database.applications().orderByChild("name"),
                glide = Glide.with(this),
                preferences = getSharedPreferences(PREFERENCE_NAME_FAVORITES, Context.MODE_PRIVATE),
                callback = object : ApplicationAdapter.Callback {

                    override fun onDataChanged() {
                        contentLoadingProgressBar.hide()
                    }

                    override fun onItemClicked(application: Application, applicationViewHolder: ApplicationViewHolder) {
                        Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, Bundle().apply {
                            putString(FirebaseAnalytics.Param.ITEM_ID, application.key)
                            putString(FirebaseAnalytics.Param.ITEM_NAME, application.name)
                        })
                        VersionsActivity.start(
                                activity = this@ApplicationsActivity,
                                application = application,
                                sharedElement = applicationViewHolder.sharedElement())
                    }

                    override fun onItemLongClicked(application: Application, applicationViewHolder: ApplicationViewHolder): Boolean {
                        applicationAdapter?.onFavoriteToggle(application)
                        return true
                    }

                }
        )

        recyclerView = findViewById<RecyclerView>(R.id.recyclerView_applications).apply {
            val orientation = RecyclerView.VERTICAL
            layoutManager = LinearLayoutManager(this@ApplicationsActivity, orientation, false)
            adapter = applicationAdapter
            setHasFixedSize(true)
            addItemDecoration(DividerItemDecoration(context, orientation).apply {
                setDrawable(ContextCompat.getDrawable(this@ApplicationsActivity, R.drawable.list_divider_with_inset)!!)
            })
            // Artificially increase the max recycled view to avoid shared transition animation glitch
            recycledViewPool.setMaxRecycledViews(0 /*default view type*/, 20)
        }
    }

    private fun updateSubtitle() {
        supportActionBar?.apply {
            Firebase.auth.currentUser.let {
                subtitle = when {
                    it == null -> null //unregistered
                    it.isAnonymous -> getString(R.string.applications_subtitle_anonymous)
                    it.isEmailVerified -> it.email
                    else -> buildSpannedString {
                        append("${it.email}")
                        color(Color.RED) {
                            bold { append(" " + getString(R.string.applications_subtitle_unverified)) }
                        }
                    }
                }
            }
        }
    }

    private fun checkStoreAccess(sendEmailVerification: Boolean = false, reloadUser: Boolean = false) {
        updateSubtitle()
        invalidateOptionsMenu()

        if (Firebase.auth.currentUser == null) {
            Firebase.unsubscribeFromStore()
            if (Store.ALLOW_ANONYMOUS) {
                signInAnonymously()
            } else {
                startActivityForResult(Firebase.signInIntent, REQUEST_CODE_AUTO_SIGN_IN)
            }
            return
        }

        Firebase.database.store().addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(error: DatabaseError) {
                when (error.code) {
                    DatabaseError.PERMISSION_DENIED -> {
                        Firebase.unsubscribeFromStore()
                        val currentUser = Firebase.auth.currentUser
                        when {
                            currentUser == null -> {
                                if (Store.ALLOW_ANONYMOUS) {
                                    signInAnonymously()
                                    return
                                } else {
                                    startActivityForResult(Firebase.signInIntent, REQUEST_CODE_AUTO_SIGN_IN)
                                    return
                                }
                            }
                            currentUser.isAnonymous -> {
                                if (Store.ALLOW_ANONYMOUS) {
                                    Log.e(TAG, "Anonymous user should be authorized")
                                } else {
                                    startActivityForResult(Firebase.signInIntent, REQUEST_CODE_AUTO_SIGN_IN)
                                    return
                                }
                            }
                            !currentUser.isEmailVerified -> {
                                if (Store.ALLOW_UNVERIFIED_EMAIL) {
                                    Log.e(TAG, "Unverified user should be authorized")
                                } else {
                                    if (sendEmailVerification) {
                                        sendVerificationEmail(currentUser)
                                        return
                                    }
                                    if (reloadUser) {
                                        currentUser.getIdToken(true).addOnSuccessListener(this@ApplicationsActivity) {
                                            currentUser.reload().addOnSuccessListener(this@ApplicationsActivity) { checkStoreAccess() }
                                        }
                                    } else {
                                        Toast.makeText(this@ApplicationsActivity, getString(R.string.applications_toast_verify_inbox), Toast.LENGTH_LONG).show()
                                    }
                                    return
                                }
                            }
                            else -> {
                                Log.e(TAG, "Current user not allowed")
                                Firebase.auth.signOut()
                                Toast.makeText(this@ApplicationsActivity, R.string.applications_toast_not_allowed, Toast.LENGTH_LONG).show()
                                wipeAndExit()
                                return
                            }
                        }
                    }
                }
                Toast.makeText(this@ApplicationsActivity, error.message, Toast.LENGTH_LONG).show()
            }

            override fun onDataChange(snapshot: DataSnapshot) {
                Firebase.subscribeToStore()
                // Only start listening when data can be successfully fetched
                applicationAdapter?.startListening()
            }
        })
    }

    private fun signInAnonymously() {
        Firebase.auth.signInAnonymously()
                .addOnSuccessListener(this) { Log.d("Store", "signInAnonymously:SUCCESS").also { checkStoreAccess() } }
                .addOnFailureListener(this) { exception -> Log.e("Store", "signInAnonymously:FAILURE", exception) }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        applicationAdapter?.filter(query)
        searchView.clearFocus()
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            applicationAdapter?.filter(query)
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_applications, menu)
        val searchItem = menu.findItem(R.id.menu_applications_search)
        searchView = searchItem.actionView as SearchView
        searchView.findViewById<SearchView.SearchAutoComplete>(R.id.search_src_text).setTextColor(ContextCompat.getColor(this, R.color.colorAccent))
        searchView.setOnQueryTextListener(this)
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus && searchView.query.isNullOrBlank()) {
                searchItem.collapseActionView()
            }
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val user = Firebase.auth.currentUser
        menu?.findItem(R.id.menu_applications_signin)?.isVisible = user == null || user.isAnonymous
        menu?.findItem(R.id.menu_applications_signout)?.isVisible = user != null && !user.isAnonymous
        menu?.findItem(R.id.menu_applications_send_verification_email)?.isVisible = user != null && !user.isAnonymous && !user.isEmailVerified
        menu?.findItem(R.id.menu_applications_refresh)?.isVisible = user != null
        menu?.findItem(R.id.menu_applications_search)?.apply {
            isVisible = user != null
            (actionView as SearchView).setQuery(applicationAdapter?.filter(), false)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_applications_signin -> {
                startActivityForResult(Firebase.signInIntent, REQUEST_CODE_MANUAL_SIGN_IN)
                true
            }
            R.id.menu_applications_signout -> {
                Firebase.auth.signOut()
                Firebase.database.store().apply {
                    keepSynced(false)
                    keepSynced(true)
                }
                recyclerView.adapter = null
                wipeAndExit()
                true
            }
            R.id.menu_applications_send_verification_email -> {
                Firebase.auth.currentUser?.apply {
                    sendEmailVerification()
                }
                true
            }
            R.id.menu_applications_refresh -> {
                Firebase.auth.currentUser?.getIdToken(true)?.addOnSuccessListener(this) {
                    Firebase.auth.currentUser?.reload()?.addOnSuccessListener(this) { checkStoreAccess() }
                }
                true
            }
            R.id.menu_applications_invalidate_cache -> {
                recyclerView.adapter = null
                invalidateCache { recreate() }
                true
            }
            R.id.menu_applications_notification_settings -> {
                safeStartActivityForResult(Utils.notificationSettingsIntent(this, null, true), REQUEST_CODE_NOTIFICATION_SETTINGS)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun invalidateCache(action: () -> Unit) {
        Firebase.storage.reference.cancelActiveDownloadTasks()
        ApkFileProvider.invalidate(applicationContext)
        val glide = Glide.get(applicationContext)
        glide.clearMemory()
        AsyncTask.execute {
            glide.clearDiskCache()
            runOnUiThread(action)
        }
    }

    private fun wipeAndExit() {
        Firebase.unsubscribeFromStore()
        Notifications.invalidate(this)
        Shortcuts.instance(this).invalidate()
        invalidateCache {
            // Force exit to prevent Firebase database in-memory cache
            if (Utils.isAtLeast(KITKAT)) {
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            } else {
                (applicationContext as Store).clearFilesCacheAndDatabases {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    private fun sendVerificationEmail(currentUser: FirebaseUser) {
        currentUser.sendEmailVerification().addOnCompleteListener(this) { task: Task<Void> ->
            if (task.isSuccessful) {
                Toast.makeText(this, R.string.applications_toast_verification_email_sent, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.applications_toast_verification_email_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_CODE_AUTO_SIGN_IN -> {
                when (resultCode) {
                    AppCompatActivity.RESULT_OK -> checkStoreAccess(sendEmailVerification = true, reloadUser = true)
                    else -> finish()
                }
            }
            REQUEST_CODE_MANUAL_SIGN_IN -> {
                when (resultCode) {
                    AppCompatActivity.RESULT_OK -> checkStoreAccess(sendEmailVerification = true, reloadUser = true)
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

}
