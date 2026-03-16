package com.wifi.visualizer

import android.app.Application
import com.wifi.visualizer.data.db.AppDatabase

/**
 * Application singleton — initialises the Room database lazily so every
 * component in the app can share the same instance without coupling to a
 * specific Activity or Fragment.
 */
class WiFiVisualizerApp : Application() {

    /** Lazy database instance shared across the whole process. */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
