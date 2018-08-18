package fr.smarquis.appstore

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.Glide
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.storage.images.FirebaseImageLoader
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
    }

    override fun onCreate() {
        super.onCreate()
        Firebase.database.store().keepSynced(true)
        Firebase.auth.addAuthStateListener { Firebase.analytics.setUserId(it.currentUser?.uid) }
        ApkFileProvider.cleanUp(this)
        checkForSelfUpdate()
        injectFirebaseImageLoader()
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

    private fun checkForSelfUpdate() {
        val myVersion = Version(name = Utils.applicationVersionName(this@Store, packageName))
        Firebase.database.applications().addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(error: DatabaseError) {}

            override fun onDataChange(dataSnapshot: DataSnapshot) {
                // find the same packageName
                for (applicationSnapshot in dataSnapshot.children) {
                    val application = Application.parse(applicationSnapshot) ?: continue
                    if (!application.isMyself(this@Store)) continue
                    // now find the most recent version
                    Firebase.database.versions(application.key.orEmpty()).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onCancelled(error: DatabaseError) {}

                        override fun onDataChange(dataSnapshot: DataSnapshot) {
                            var compareTo = myVersion
                            for (versionSnapshot in dataSnapshot.children) {
                                // only use versions without label
                                val version = Version.parse(versionSnapshot) ?: continue
                                if (!version.semver.label.isNullOrBlank()) continue
                                // ignore same versions
                                if (version.semver > compareTo.semver) {
                                    compareTo = version
                                }
                            }
                            if (compareTo != myVersion) {
                                Notifications.onNewVersion(this@Store, application, compareTo)
                            }
                        }
                    })
                    break
                }
            }
        })
    }

    private fun injectFirebaseImageLoader() {
        Glide.get(this).registry.append(StorageReference::class.java, InputStream::class.java, FirebaseImageLoader.Factory())
    }

}