package com.voiceping.offlinetranscription

import android.app.Application
import android.os.Build
import android.util.Log
import com.cactus.CactusContextInitializer
import com.voiceping.offlinetranscription.data.AppPreferences
import com.voiceping.offlinetranscription.service.WhisperEngine

class OfflineTranscriptionApp : Application() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var whisperEngine: WhisperEngine
        private set

    override fun onCreate() {
        super.onCreate()
        // Cactus 1.4.1-beta ships arm64-v8a native libs only.
        // Keep app startup safe on x86_64 emulators by initializing conditionally.
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            try {
                CactusContextInitializer.initialize(this)
            } catch (e: Throwable) {
                Log.w("OfflineTranscriptionApp", "Cactus initialization failed", e)
            }
        } else {
            Log.i("OfflineTranscriptionApp", "Skipping Cactus init on ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
        preferences = AppPreferences(this)
        whisperEngine = WhisperEngine(this, preferences)
    }

    override fun onTerminate() {
        super.onTerminate()
        whisperEngine.destroy()
    }
}
