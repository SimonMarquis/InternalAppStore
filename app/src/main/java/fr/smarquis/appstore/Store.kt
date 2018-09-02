package fr.smarquis.appstore

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import androidx.core.provider.FontRequest
import androidx.emoji.text.EmojiCompat
import androidx.emoji.text.FontRequestEmojiCompatConfig
import com.bumptech.glide.Glide
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.storage.images.FirebaseImageLoader
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.StorageReference
import java.io.InputStream

class Store : android.app.Application() {

    companion object Config {

        val ALLOW_ANONYMOUS by lazy { false }
        val ALLOW_UNVERIFIED_EMAIL by lazy { false }
        val ANALYTICS_COLLECTION by lazy { true }
        val AUTH_PROVIDERS by lazy {
            listOf(
                    AuthUI.IdpConfig.EmailBuilder().setRequireName(false).build(),
                    AuthUI.IdpConfig.GoogleBuilder().build())
        }

        val SCREENSHOT_STATUS: Pair<Version.Status, Int>? by lazy {
            /*Version.Status.DOWNLOADING to 66*/
            /*Version.Status.INSTALLING*/
            /*Version.Status.DEFAULT to 0*/
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Firebase.database.store().keepSynced(true)
        Firebase.auth.addAuthStateListener { Firebase.analytics.setUserId(it.currentUser?.uid) }
        ApkFileProvider.cleanUp(this)
        injectFirebaseImageLoader()
        synchronizeNotificationChannels()
        initEmojiCompat()
    }

    private fun initEmojiCompat() {
        val fontRequest = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs)
        EmojiCompat.init(FontRequestEmojiCompatConfig(applicationContext, fontRequest).setReplaceAll(true))
    }

    fun clearFilesCacheAndDatabases(action: (() -> Unit)? = null) {
        AsyncTask.execute {
            cacheDir.deleteRecursively()
            filesDir.deleteRecursively()
            for (database in databaseList()) {
                deleteDatabase(database)
            }
            action?.let { Handler(Looper.getMainLooper()).post(it) }
        }
    }

    private fun synchronizeNotificationChannels() {
        Notifications.createOrUpdateNewApplicationsNotificationChannel(this)
        Firebase.database.applications().addChildEventListener(object : ChildEventListener {

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val application = Application.parse(snapshot) ?: return
                Notifications.createOrUpdateNewVersionsNotificationChannel(this@Store, application)
                if (application.isMyself(this@Store)) {
                    checkForSelfUpdate(application)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                Notifications.deleteNewVersionsNotificationChannel(this@Store, Application.parse(snapshot)
                        ?: return)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun checkForSelfUpdate(application: Application) {
        val myself = Version(name = Utils.applicationVersionName(this@Store, packageName))
        Firebase.database.versions(application.key.orEmpty()).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(error: DatabaseError) {}

            override fun onDataChange(dataSnapshot: DataSnapshot) {
                var compareTo = myself
                for (versionSnapshot in dataSnapshot.children) {
                    // only use versions without label
                    val version = Version.parse(versionSnapshot) ?: continue
                    if (!version.semver.label.isNullOrBlank()) continue
                    // ignore same versions
                    if (version.semver > compareTo.semver) {
                        compareTo = version
                    }
                }
                if (compareTo != myself) {
                    Notifications.onNewVersion(this@Store, application, compareTo)
                }
            }
        })
    }

    private fun injectFirebaseImageLoader() {
        Glide.get(this).registry.append(StorageReference::class.java, InputStream::class.java, FirebaseImageLoader.Factory())
    }

}