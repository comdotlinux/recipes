package com.linux.recipes.android

import android.app.Activity
import android.app.Application
import com.linux.recipes.AppGraph
import com.linux.recipes.platform.AndroidSecureStore
import java.io.File
import java.lang.ref.WeakReference

/** Owns the [AppGraph] for the whole process, so it survives activity recreation. */
class RecipesApplication : Application() {
    /** The activity on screen; Credential Manager shows its UI on top of it. */
    @Volatile
    var currentActivity: WeakReference<Activity>? = null

    val graph: AppGraph by lazy {
        AppGraph(
            filesDir = filesDir.path,
            // noBackupFilesDir is never backed up; data_extraction_rules.xml also excludes it from device transfer.
            secureStore = AndroidSecureStore(File(noBackupFilesDir, "secure")),
            authProvider = BuildVariant.authProvider { currentActivity?.get() },
            baseUrl = BuildVariant.baseUrl,
            billingProvider = BuildVariant::billingProvider,
        )
    }
}
