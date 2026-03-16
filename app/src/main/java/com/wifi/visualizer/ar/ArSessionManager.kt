package com.wifi.visualizer.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*

private const val TAG = "ArSessionManager"

/**
 * Centralises ARCore [Session] lifecycle management.
 *
 * Separating this from the Activity keeps the AR setup logic testable and
 * prevents the boilerplate from cluttering [ArMappingActivity].
 */
class ArSessionManager(private val context: Context) {

    var session: Session? = null
        private set

    var isDepthSupported: Boolean = false
        private set

    /**
     * Checks whether ARCore is installed and up-to-date on this device.
     *
     * @return [ArAvailability] indicating what the caller should do next.
     */
    fun checkArAvailability(): ArAvailability {
        return when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED   -> ArAvailability.READY
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.NEEDS_INSTALL
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.NOT_SUPPORTED
            else -> ArAvailability.UNKNOWN
        }
    }

    /**
     * Creates and configures an ARCore [Session].
     *
     * @throws UnavailableException subclasses if ARCore is not ready.
     */
    fun createSession(): Session {
        val newSession = Session(context)

        // Configure depth if the device supports it
        isDepthSupported = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

        val config = Config(newSession).apply {
            updateMode  = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            if (isDepthSupported) {
                depthMode = Config.DepthMode.AUTOMATIC
            }
        }
        newSession.configure(config)
        session = newSession

        Log.i(TAG, "ARCore session created. Depth supported: $isDepthSupported")
        return newSession
    }

    fun resume() {
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available: ${e.message}")
            session = null
        }
    }

    fun pause() {
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }
}

enum class ArAvailability { READY, NEEDS_INSTALL, NOT_SUPPORTED, UNKNOWN }
