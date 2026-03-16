package com.wifi.visualizer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.wifi.visualizer.data.model.SessionMetadata
import com.wifi.visualizer.data.model.SignalSample
import com.wifi.visualizer.data.model.WifiScanResult

/**
 * Single Room database for the application.
 *
 * Version history:
 *   1 → initial schema (WifiScanResult)
 *   2 → added SignalSample and SessionMetadata
 *
 * Schema JSON files are exported to app/schemas/ (configured in build.gradle.kts via KSP)
 * and are committed to source control so migration integrity can be verified in CI.
 */
@Suppress("DEPRECATION") // fallbackToDestructiveMigration is intentional for v1→v2
@Database(
    entities     = [WifiScanResult::class, SignalSample::class, SessionMetadata::class],
    version      = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wifiScanDao(): WifiScanDao
    abstract fun signalSampleDao(): SignalSampleDao
    abstract fun sessionMetadataDao(): SessionMetadataDao

    companion object {
        private const val DB_NAME = "wifi_visualizer.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
