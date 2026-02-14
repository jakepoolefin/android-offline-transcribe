package com.voiceping.offlinetranscription.service

import com.voiceping.offlinetranscription.model.CactusModelType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CactusEngineTest {

    @Test
    fun float32ToPcm16_silence_producesZeroBytes() {
        val silence = FloatArray(100) { 0f }
        val pcm = CactusEngine.float32ToPcm16(silence)
        assertEquals(200, pcm.size, "Each float produces 2 bytes")
        pcm.forEach { assertEquals(0, it, "Silence should produce zero bytes") }
    }

    @Test
    fun float32ToPcm16_maxPositive_produces7FFF() {
        val samples = floatArrayOf(1.0f)
        val pcm = CactusEngine.float32ToPcm16(samples)
        // 32767 = 0x7FFF, little-endian: [0xFF, 0x7F]
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
    }

    @Test
    fun float32ToPcm16_maxNegative_produces8001() {
        val samples = floatArrayOf(-1.0f)
        val pcm = CactusEngine.float32ToPcm16(samples)
        // -32767 as short = 0x8001, little-endian: [0x01, 0x80]
        assertEquals(0x01.toByte(), pcm[0])
        assertEquals(0x80.toByte(), pcm[1])
    }

    @Test
    fun float32ToPcm16_clampsOverflow() {
        val samples = floatArrayOf(2.0f, -2.0f)
        val pcm = CactusEngine.float32ToPcm16(samples)
        // 2.0 clamped to 1.0 -> 32767, -2.0 clamped to -1.0 -> -32767
        assertEquals(4, pcm.size)
        // First sample: 0x7FFF
        assertEquals(0xFF.toByte(), pcm[0])
        assertEquals(0x7F.toByte(), pcm[1])
        // Second sample: 0x8001
        assertEquals(0x01.toByte(), pcm[2])
        assertEquals(0x80.toByte(), pcm[3])
    }

    @Test
    fun float32ToPcm16_outputSize_isDoubleInputSize() {
        val sizes = listOf(0, 1, 100, 16000)
        sizes.forEach { size ->
            val samples = FloatArray(size)
            val pcm = CactusEngine.float32ToPcm16(samples)
            assertEquals(size * 2, pcm.size, "PCM size should be 2x float size for $size samples")
        }
    }

    @Test
    fun cactusModelName_whisper_returnsWhisperTiny() {
        val engine = CactusEngine(CactusModelType.WHISPER)
        assertEquals("whisper-tiny", engine.cactusModelName())
    }

    @Test
    fun cactusModelName_moonshine_returnsMoonshineBase() {
        val engine = CactusEngine(CactusModelType.MOONSHINE)
        assertEquals("moonshine-base", engine.cactusModelName())
    }

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
}
