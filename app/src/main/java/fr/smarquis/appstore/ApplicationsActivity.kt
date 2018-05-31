package fr.smarquis.appstore

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.support.v4.widget.ContentLoadingProgressBar
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener


class ApplicationsActivity : AppCompatActivity() {

    companion object {

        private const val TAG = "AppStore"

        const val REQUEST_CODE_AUTO_SIGN_IN = 1
        const val REQUEST_CODE_MANUAL_SIGN_IN = 2
        const val REQUEST_CODE_NOTIFICATION_SETTINGS = 3

    }

    private var applicationAdapter: ApplicationAdapter? = null
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar
    private lateinit var recyclerView: RecyclerView

    private val dataObserver: RecyclerView.AdapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            contentLoadingProgressBar.hide()
            updateNotificationChannels(positionStart, itemCount)
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            super.onItemRangeChanged(positionStart, itemCount)
            updateNotificationChannels(positionStart, itemCount)
        }

        private fun updateNotificationChannels(positionStart: Int, itemCount: Int) {
            for (index in positionStart until positionStart + itemCount) {
                applicationAdapter?.getItem(index)?.let {
                    MessagingService.createOrUpdateNewVersionsNotificationChannel(this@ApplicationsActivity, getString(R.string.notification_channel_new_versions_id, it.key), it.name)
                }
            }
        }
    }

    private val packageIntentFilterReceiver = PackageIntentFilter.receiver({ _: String, packageName: String -> applicationAdapter?.onPackageChanged(packageName) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_applications)
        initUi()
        registerListeners()
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
        Firebase.auth.currentUser?.let {
            if (!it.isEmailVerified) {
                checkStoreAccess(sendEmailVerification = false, reloadUser = true)
            }
        }
    }

    override fun onDestroy() {
        unregisterListeners()
        super.onDestroy()
    }

    private fun initUi() {
        contentLoadingProgressBar = findViewById(R.id.contentLoadingProgressBar_applications)
        contentLoadingProgressBar.show()

        applicationAdapter = ApplicationAdapter(
                query = Firebase.database.applications().orderByChild("name"),
                glide = GlideApp.with(this),
                callback = object : ApplicationAdapter.Callback {
                    override fun onItemClicked(application: Application, applicationViewHolder: ApplicationViewHolder) {
                        Firebase.analytics.logEvent(FirebaseAnalytics.Event.VIEW_ITEM, Bundle().apply {
                            putString(FirebaseAnalytics.Param.ITEM_ID, application.key)
                            putString(FirebaseAnalytics.Param.ITEM_NAME, application.name)
                        })
                        VersionsActivity.start(this@ApplicationsActivity, application, applicationViewHolder.sharedElement())
                    }
                }
        )

        recyclerView = findViewById<RecyclerView>(R.id.recyclerView_applications).apply {
            val orientation = RecyclerView.VERTICAL
            layoutManager = LinearLayoutManager(this@ApplicationsActivity, orientation, false)
            adapter = applicationAdapter
            setHasFixedSize(true)
            addItemDecoration(DividerItemDecoration(context, orientation))
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
                                        currentUser.getIdToken(true).addOnSuccessListener(this@ApplicationsActivity, {
                                            currentUser.reload().addOnSuccessListener(this@ApplicationsActivity, { checkStoreAccess() })
                                        })
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
                applicationAdapter?.apply {
                    stopListening()
                    startListening()
                }
            }
        })
    }

    private fun signInAnonymously() {
        Firebase.auth.signInAnonymously()
                .addOnSuccessListener(this@ApplicationsActivity, { Log.d("Store", "signInAnonymously:SUCCESS").also { checkStoreAccess() } })
                .addOnFailureListener(this@ApplicationsActivity, { exception -> Log.e("Store", "signInAnonymously:FAILURE", exception) })
    }

    private fun registerListeners() {
        applicationAdapter?.registerAdapterDataObserver(dataObserver)
        PackageIntentFilter.register(this, packageIntentFilterReceiver)
    }

    private fun unregisterListeners() {
        PackageIntentFilter.unregister(this, packageIntentFilterReceiver)
        applicationAdapter?.stopListening()
        applicationAdapter?.unregisterAdapterDataObserver(dataObserver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_applications, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val user = Firebase.auth.currentUser
        menu?.findItem(R.id.menu_action_signin)?.isVisible = user == null || user.isAnonymous
        menu?.findItem(R.id.menu_action_signout)?.isVisible = user != null && !user.isAnonymous
        menu?.findItem(R.id.menu_action_send_verification_email)?.isVisible = user != null && !user.isAnonymous && !user.isEmailVerified
        menu?.findItem(R.id.menu_action_refresh)?.isVisible = user != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_action_signin -> {
                startActivityForResult(Firebase.signInIntent, REQUEST_CODE_MANUAL_SIGN_IN)
                true
            }
            R.id.menu_action_signout -> {
                Firebase.auth.signOut()
                Firebase.database.store().apply {
                    keepSynced(false)
                    keepSynced(true)
                }
                recyclerView.adapter = null
                wipeAndExit()
                true
            }
            R.id.menu_action_send_verification_email -> {
                Firebase.auth.currentUser?.apply {
                    sendEmailVerification()
                }
                true
            }
            R.id.menu_action_refresh -> {
                Firebase.auth.currentUser?.getIdToken(true)?.addOnSuccessListener(this@ApplicationsActivity, {
                    Firebase.auth.currentUser?.reload()?.addOnSuccessListener(this@ApplicationsActivity, { checkStoreAccess() })
                })
                true
            }
            R.id.menu_action_invalidate_cache -> {
                recyclerView.adapter = null
                invalidateCache { recreate() }
                true
            }
            R.id.menu_action_notification_settings -> {
                MessagingService.createOrUpdateNewApplicationsNotificationChannel(this)
                val channelId = getString(R.string.notification_channel_new_applications_id)
                safeStartActivityForResult(Utils.notificationSettingsIntent(this, channelId, true), REQUEST_CODE_NOTIFICATION_SETTINGS)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun invalidateCache(action: () -> Unit) {
        Firebase.storage.reference.cancelActiveDownloadTasks()
        ApkFileProvider.invalidate(applicationContext)
        val glide = GlideApp.get(applicationContext)
        glide.clearMemory()
        AsyncTask.execute({
            glide.clearDiskCache()
            runOnUiThread(action)
        })
    }

    private fun wipeAndExit() {
        Firebase.unsubscribeFromStore()
        MessagingService.cancelAllNotifications(this@ApplicationsActivity)
        invalidateCache {
            // Force exit to prevent Firebase database in-memory cache
            if (Utils.isAtLeast(Build.VERSION_CODES.KITKAT)) {
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            } else {
                (applicationContext as Store).clearFilesCacheAndDatabases {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    private fun sendVerificationEmail(currentUser: FirebaseUser) {
        currentUser.sendEmailVerification().addOnCompleteListener(this@ApplicationsActivity, { task: Task<Void> ->
            if (task.isSuccessful) {
                Toast.makeText(this@ApplicationsActivity, R.string.applications_toast_verification_email_sent, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ApplicationsActivity, R.string.applications_toast_verification_email_error, Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult(requestCode:$requestCode, resultCode:$resultCode, data:$data)")
        when (requestCode) {
            REQUEST_CODE_AUTO_SIGN_IN -> {
                when (resultCode) {
                    Activity.RESULT_OK -> checkStoreAccess(sendEmailVerification = true, reloadUser = true)
                    else -> finish()
                }
            }
            REQUEST_CODE_MANUAL_SIGN_IN -> {
                when (resultCode) {
                    Activity.RESULT_OK -> checkStoreAccess(sendEmailVerification = true, reloadUser = true)
                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

}
