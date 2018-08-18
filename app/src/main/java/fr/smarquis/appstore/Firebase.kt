package fr.smarquis.appstore

import com.firebase.ui.auth.AuthUI
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage


class Firebase {

    companion object {

        val app: FirebaseApp by lazy { FirebaseApp.getInstance()!! }

        val analytics: FirebaseAnalytics by lazy {
            FirebaseAnalytics.getInstance(app.applicationContext).apply {
                setAnalyticsCollectionEnabled(Store.ANALYTICS_COLLECTION)
            }
        }

        val database: FirebaseDatabase by lazy {
            FirebaseDatabase.getInstance(app).apply {
                setPersistenceEnabled(true)
            }
        }

        val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance(app) }

        val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(app) }

        val messaging: FirebaseMessaging by lazy { FirebaseMessaging.getInstance() }

        val signInIntent by lazy {
            AuthUI.getInstance(Firebase.app)
                    .createSignInIntentBuilder()
                    .setTheme(R.style.Theme_AppStore)
                    .setAvailableProviders(Store.AUTH_PROVIDERS)
                    .setIsSmartLockEnabled(!BuildConfig.DEBUG, true)
                    .setLogo(R.drawable.ic_launcher_web)
                    .build()
        }

        fun subscribeToStore() {
            Firebase.messaging.subscribeToTopic("store")
        }

        fun unsubscribeFromStore() {
            Firebase.messaging.unsubscribeFromTopic("store")
        }

    }
}