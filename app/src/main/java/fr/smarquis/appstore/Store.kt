package fr.smarquis.appstore

import android.app.Application
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import com.firebase.ui.auth.AuthUI

class Store : Application() {

    companion object Config {

        val ALLOW_ANONYMOUS by lazy { false }
        val ALLOW_UNVERIFIED_EMAIL by lazy { false }
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

}