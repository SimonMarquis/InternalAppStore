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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.storage.StorageReference
import java.io.InputStream

class Store : android.app.Application() {

    companion object Config {

        val ALLOW_ANONYMOUS by lazy { false }
        val ALLOW_UNVERIFIED_EMAIL by lazy { false }
        val ANALYTICS_COLLECTION by lazy { true }
        val SMART_LOCK by lazy { !BuildConfig.DEBUG }
        val AUTH_PROVIDERS by lazy {
            listOf(
                AuthUI.IdpConfig.EmailBuilder().setRequireName(false).build(),
                AuthUI.IdpConfig.GoogleBuilder().build(),
            )
        }

    }

    override fun onCreate() {
        super.onCreate()
        Firebase.database.store().keepSynced(true)
        Firebase.auth.addAuthStateListener { Firebase.analytics.setUserId(it.currentUser?.uid) }
        Notifications.createOrUpdateNewApplicationsNotificationChannel(this)
        ApkFileProvider.cleanUp(this)
        injectFirebaseImageLoader()
        injectFirebaseDatabaseListeners()
        initEmojiCompat()
    }

    private fun initEmojiCompat() {
        val fontRequest = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            "Noto Color Emoji Compat",
            R.array.com_google_android_gms_fonts_certs,
        )
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

    /**
     * This method will inject database listeners to:
     * - check for self update (based on package name)
     * - synchronize notification channels and shortcuts
     * - detect unreferenced apk files and delete them
     */
    private fun injectFirebaseDatabaseListeners() {
        val store = this
        val shortcuts by lazy { Shortcuts.instance(store) }
        val detectRemovedVersions = RemovedVersionsEventListener(store)

        Firebase.database.applications().addChildEventListener(
            object : AbstractChildEventListener() {

                override fun onChildAdded(snapshot: DataSnapshot, previousChildKey: String?) {
                    Application.parse(snapshot)?.let {
                        Notifications.createOrUpdateNewVersionsNotificationChannel(store, it)
                        with(Firebase.database.versions(it.key.orEmpty())) {
                            addChildEventListener(detectRemovedVersions)
                            if (it.isMyself(store)) {
                                // SingleValueEvent will be enough
                                // since regular updates will still be delivered through notifications
                                addListenerForSingleValueEvent(SelfUpdateEventListener(store, it))
                            }
                        }
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildKey: String?) {
                    Application.parse(snapshot)?.let {
                        Notifications.createOrUpdateNewVersionsNotificationChannel(store, it)
                        shortcuts.update(it)
                    }
                }

                override fun onChildRemoved(snapshot: DataSnapshot) {
                    Application.parse(snapshot)?.let {
                        Notifications.deleteNewVersionsNotificationChannel(store, it)
                        Firebase.database.versions(it.key.orEmpty()).removeEventListener(detectRemovedVersions)
                        shortcuts.remove(it)
                    }
                }

            },
        )

    }

    private fun injectFirebaseImageLoader() {
        Glide.get(this).registry.append(StorageReference::class.java, InputStream::class.java, FirebaseImageLoader.Factory())
    }

}
