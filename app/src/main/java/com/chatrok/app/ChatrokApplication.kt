package com.chatrok.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.chatrok.app.domain.repository.PresenceRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ChatrokApplication : Application() {

    @Inject
    lateinit var presenceRepository: dagger.Lazy<PresenceRepository>

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        initAppCheck()
        com.chatrok.app.core.push.NotificationChannelsHelper.createNotificationChannels(this)
        registerLifecyclePresence()
    }

    private fun registerLifecyclePresence() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            presenceRepository.get().setPresence(true)
                        } catch (_: Exception) {}
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            presenceRepository.get().setPresence(false)
                        } catch (_: Exception) {}
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.fromResource(this)
                if (options != null) {
                    FirebaseApp.initializeApp(this, options)
                    Log.i(TAG, "FirebaseApp initialized from resources")
                } else {
                    val fallbackOptions = FirebaseOptions.Builder()
                        .setApplicationId("1:827350687574:android:e556831d725d637c7840f7")
                        .setApiKey("AIzaSyCKC94pHAPW16iotmPOaVKbmJzKsYmbrsM")
                        .setProjectId("chatrok-4b121")
                        .setStorageBucket("chatrok-4b121.firebasestorage.app")
                        .setGcmSenderId("827350687574")
                        .build()
                    FirebaseApp.initializeApp(this, fallbackOptions)
                    Log.i(TAG, "FirebaseApp initialized from fallback")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing FirebaseApp, attempting fallback", e)
            try {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    val fallbackOptions = FirebaseOptions.Builder()
                        .setApplicationId("1:827350687574:android:e556831d725d637c7840f7")
                        .setApiKey("AIzaSyCKC94pHAPW16iotmPOaVKbmJzKsYmbrsM")
                        .setProjectId("chatrok-4b121")
                        .setStorageBucket("chatrok-4b121.firebasestorage.app")
                        .setGcmSenderId("827350687574")
                        .build()
                    FirebaseApp.initializeApp(this, fallbackOptions)
                }
            } catch (fatal: Exception) {
                Log.e(TAG, "Fatal error initializing fallback FirebaseApp", fatal)
            }
        }
    }

    private fun initAppCheck() {
        try {
            val appCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(
                    com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
                )
                Log.i(TAG, "Firebase App Check initialized with DebugAppCheckProviderFactory")
            } else {
                appCheck.installAppCheckProviderFactory(
                    com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
                )
                Log.i(TAG, "Firebase App Check initialized with PlayIntegrityAppCheckProviderFactory")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase App Check initialization deferred: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ChatrokApplication"
    }
}
