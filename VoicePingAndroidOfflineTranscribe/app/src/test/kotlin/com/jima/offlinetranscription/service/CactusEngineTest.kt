package com.voiceping.offlinetranscription.service

import com.voiceping.offlinetranscription.model.CactusModelType
import org.junit.Test
import kotlin.test.assertFalse

class CactusEngineTest {

    @Test
    fun newEngine_isNotLoaded() {
        val engine = CactusEngine(CactusModelType.WHISPER)
        assertFalse(engine.isLoaded)
    }

    @Test
    fun release_setsLoadedFalse() {
        val engine = CactusEngine(CactusModelType.WHISPER)
        engine.release()
        assertFalse(engine.isLoaded)
    }

    @Test
    fun isStreaming_defaultsFalse() {
        val engine = CactusEngine(CactusModelType.WHISPER)
        assertFalse(engine.isStreaming)
    }

    @Test
    fun isRuntimeSupported_returnsFalseInUnitTest() {
        // Build.SUPPORTED_ABIS is null in unit tests, so this should return false
        assertFalse(CactusEngine.isRuntimeSupported())
    }
}
