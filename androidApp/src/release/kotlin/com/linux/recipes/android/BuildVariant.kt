package com.linux.recipes.android

import android.app.Activity
import com.linux.recipes.AppConfig
import com.linux.recipes.platform.AuthProvider
import com.linux.recipes.platform.BillingProvider
import com.linux.recipes.platform.RevenueCatBillingProvider
import com.linux.recipes.platform.GoogleAuthProvider

/** Release builds: the production base URL and Google sign-in. The debug source set has its own BuildVariant. */
object BuildVariant {
    val baseUrl: String = AppConfig.API_BASE_URL

    fun authProvider(activity: () -> Activity?): AuthProvider = GoogleAuthProvider(AppConfig.GOOGLE_SERVER_CLIENT_ID, activity)

    @Suppress("UNUSED_PARAMETER")
    fun billingProvider(sessionToken: () -> String?): BillingProvider =
        RevenueCatBillingProvider(AppConfig.REVENUECAT_ANDROID_KEY, "https://play.google.com/store/account/subscriptions")
}
