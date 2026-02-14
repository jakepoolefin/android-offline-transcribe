package com.voiceping.offlinetranscription.e2e

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * E2E UI test that launches the app with each model, waits for transcription,
 * and captures screenshot evidence at each step.
 *
 * Evidence is written to /sdcard/Documents/e2e/{modelId}/ with:
 *   01_model_selected.png  — right after app launch (model downloading/loading)
 *   02_model_loaded.png    — when transcription screen is visible (model loaded)
 *   03_inference_result.png — after transcription completes (with E2E overlay)
 *   result.json            — machine-readable pass/fail + transcript
 */
class AllModelsE2ETest {
    companion object {
        private const val TAG = "E2E"
        private const val PACKAGE = "com.voiceping.transcribe"
    }

    private lateinit var device: UiDevice
    private lateinit var context: Context

    // Per-model download+load+transcribe timeout (ms)
    // whisper.cpp on emulator is very slow — whisper-small needs ~500s, large-turbo ~1800s
    private fun timeout(modelId: String): Long = when {
        modelId.contains("android-speech") -> 60_000L // 1 min — system-provided, no download
        modelId.contains("qwen") -> 600_000L     // 10 min — 600M+ model, ORT fallback adds time
        modelId.contains("cactus") -> 600_000L   // 10 min — download + init may exceed the default 2m on slow networks
        modelId.contains("large") -> 3_600_000L  // 60 min — 809M model on older phones
        modelId.contains("omnilingual") -> 600_000L
        modelId.contains("zipformer") -> 300_000L
        modelId.contains("small") -> 1_200_000L
        modelId.contains("base") -> 600_000L
        else -> 120_000L
    }

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().context
    }

    // ---- Individual model tests ----

    @Test fun test_whisperTiny(): Unit = testModel("whisper-tiny")
    @Test fun test_whisperBase(): Unit = testModel("whisper-base")
    @Test fun test_whisperBaseEn(): Unit = testModel("whisper-base-en")
    @Test fun test_whisperSmall(): Unit = testModel("whisper-small")
    @Test fun test_whisperLargeV3Turbo(): Unit = testModel("whisper-large-v3-turbo")
    @Test fun test_moonshineTiny(): Unit = testModel("moonshine-tiny")
    @Test fun test_moonshineBase(): Unit = testModel("moonshine-base")
    @Test fun test_sensevoiceSmall(): Unit = testModel("sensevoice-small")
    @Test fun test_omnilingual300m(): Unit = testModel("omnilingual-300m")
    @Test fun test_zipformer20m(): Unit = testModel("zipformer-20m")
    @Test fun test_cactusWhisperTiny(): Unit = testModel("cactus-whisper-tiny")
    @Test fun test_qwen3Asr06bCpu(): Unit = testModel("qwen3-asr-0.6b")
    @Test fun test_qwen3Asr06bOnnx(): Unit = testModel("qwen3-asr-0.6b-onnx")
    @Test fun test_androidSpeechOffline(): Unit = testModel("android-speech-offline")
    @Test fun test_androidSpeechOnline(): Unit = testModel("android-speech-online")

    // ---- Core test logic ----

    private fun testModel(modelId: String) {
        val evidenceDir = "/sdcard/Documents/e2e/$modelId"
        val resultSrcPrivate = "/sdcard/Android/data/$PACKAGE/files/e2e_result_$modelId.json"
        val resultSrcPublic = "$evidenceDir/result.json"
        val timeoutMs = timeout(modelId)

        Log.i(TAG, "=== E2E Testing $modelId (timeout: ${timeoutMs / 1000}s) ===")

        // 1. Create evidence directory and clean up previous result
        File(evidenceDir).mkdirs()
        File(resultSrcPrivate).delete()
        File(resultSrcPublic).delete()

        // 2. Launch app via Intent (avoids am force-stop which kills test process)
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("e2e_test", true)
            putExtra("model_id", modelId)
        }
        context.startActivity(intent)
        Log.i(TAG, "[$modelId] App launched with e2e_test=true")

        // 3. Wait for app to start, then screenshot
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
        Thread.sleep(3000)
        dismissSystemUiDialogIfPresent(modelId)
        takeScreenshot(evidenceDir, "01_model_selected.png")
        Log.i(TAG, "[$modelId] Screenshot 01 captured")

        // 4. Wait for transcription screen (model loaded) — look for "CPU" text in stats bar.
        // Poll in a loop so we can dismiss blocking system dialogs (ANR popups) during long loads.
        val loadStart = System.currentTimeMillis()
        var cpuFound = false
        while (System.currentTimeMillis() - loadStart < timeoutMs) {
            dismissSystemUiDialogIfPresent(modelId)
            val hasCpuStats = device.hasObject(By.textContains("CPU"))
            val hasEarlyResult = File(resultSrcPublic).exists() || File(resultSrcPrivate).exists()
            if (hasCpuStats || hasEarlyResult) {
                cpuFound = true
                break
            }
            Thread.sleep(1000)
        }
        if (cpuFound) {
            Log.i(TAG, "[$modelId] Model loaded — transcription screen visible")
        } else {
            Log.w(TAG, "[$modelId] Timeout waiting for model load")
        }
        takeScreenshot(evidenceDir, "02_model_loaded.png")
        Log.i(TAG, "[$modelId] Screenshot 02 captured")

        // 5. Wait for result.json or E2E overlay to appear
        // whisper.cpp on emulator is very slow — whisper-small needs ~600s for inference
        val evidenceTimeout = timeoutMs
        val startWait = System.currentTimeMillis()
        var resultExists = false
        while (System.currentTimeMillis() - startWait < evidenceTimeout) {
            dismissSystemUiDialogIfPresent(modelId)
            // Check if result.json was written (fast path)
            if (File(resultSrcPublic).exists() || File(resultSrcPrivate).exists()) {
                resultExists = true
                Log.i(TAG, "[$modelId] result.json detected")
                break
            }
            // Check for E2E evidence overlay in UI
            val overlayElement = device.findObject(By.textContains("E2E EVIDENCE"))
            if (overlayElement != null) {
                Log.i(TAG, "[$modelId] E2E overlay detected")
                resultExists = true
                break
            }
            Thread.sleep(2000)
        }

        // Give UI a moment to settle for final screenshot
        Thread.sleep(3000)
        takeScreenshot(evidenceDir, "03_inference_result.png")
        Log.i(TAG, "[$modelId] Screenshot 03 captured")

        // 6. Copy result.json to evidence directory and validate
        if (!resultExists) {
            resultExists = File(resultSrcPublic).exists() || File(resultSrcPrivate).exists()
        }
        if (resultExists) {
            val publicFile = File(resultSrcPublic)
            val privateFile = File(resultSrcPrivate)
            val srcFile = when {
                publicFile.exists() -> publicFile
                privateFile.exists() -> privateFile
                else -> null
            }
            val resolvedFile = srcFile ?: run {
                val missingJson = """{"model_id":"$modelId","pass":false,"error":"result marker without result.json"}"""
                publicFile.writeText(missingJson)
                throw AssertionError("[$modelId] resultExists=true but result.json source file missing")
            }
            if (resolvedFile.absolutePath != publicFile.absolutePath) {
                resolvedFile.copyTo(publicFile, overwrite = true)
            }
            val json = resolvedFile.readText().trim()
            Log.i(TAG, "[$modelId] result.json: $json")
            val payload = JSONObject(json)

            val pass = payload.optBoolean("pass", false)
            val skipped = payload.optBoolean("skipped", false)
            if (skipped) {
                Log.w(TAG, "[$modelId] E2E skipped: ${payload.optString("error", "").take(120)}")
            }
            assertTrue(
                pass || skipped,
                "[$modelId] Expected pass=true or skipped=true in result.json, got: $json"
            )
            // Log translation evidence (informational, not required for pass)
            if (payload.optBoolean("expects_translation", false)) {
                val translatedText = payload.optString("translated_text", "").trim()
                if (translatedText.isEmpty()) {
                    Log.w(TAG, "[$modelId] WARNING: translation enabled but translated_text is empty")
                } else {
                    Log.i(TAG, "[$modelId] Translation evidence: ${translatedText.take(80)}...")
                }
            }
        } else {
            Log.e(TAG, "[$modelId] No result.json found after timeout")
            val timeoutJson = """{"model_id":"$modelId","pass":false,"error":"timeout"}"""
            File(resultSrcPublic).writeText(timeoutJson)
            assertTrue(false, "[$modelId] Timed out waiting for transcription result")
        }

        Log.i(TAG, "[$modelId] E2E PASSED")
    }

    /**
     * Emulator-heavy model runs can trigger Android system ANR dialogs
     * ("System UI isn't responding"), which block UiAutomator waits.
     * Prefer "Wait" to keep the app process alive.
     */
    private fun dismissSystemUiDialogIfPresent(modelId: String): Boolean {
        return try {
            val anrTitle = device.findObject(By.textContains("isn't responding"))
                ?: device.findObject(By.textContains("keeps stopping"))
                ?: return false

            val waitButton = device.findObject(By.textContains("Wait"))
                ?: device.findObject(By.textContains("wait"))
            if (waitButton != null) {
                waitButton.click()
                Log.w(TAG, "[$modelId] Dismissed system dialog with 'Wait': ${anrTitle.safeText()}")
                Thread.sleep(500)
                return true
            }

            val closeButton = device.findObject(By.textContains("Close app"))
                ?: device.findObject(By.textContains("close app"))
            if (closeButton != null) {
                closeButton.click()
                Log.w(TAG, "[$modelId] Dismissed system dialog with 'Close app': ${anrTitle.safeText()}")
                Thread.sleep(500)
                return true
            }

            // Last resort: back out of modal dialog
            device.pressBack()
            Thread.sleep(300)
            Log.w(TAG, "[$modelId] Dismissed system dialog via back press")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "[$modelId] Failed to inspect/dismiss system dialog", t)
            false
        }
    }

    private fun androidx.test.uiautomator.UiObject2.safeText(): String {
        return try {
            text ?: "<no-text>"
        } catch (_: Throwable) {
            "<stale-object>"
        }
    }

    private fun takeScreenshot(dir: String, filename: String) {
        try {
            val destFile = File(dir, filename)
            destFile.parentFile?.mkdirs()
            device.takeScreenshot(destFile)
            Log.d(TAG, "Screenshot saved: ${destFile.absolutePath} (${destFile.length()} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "Screenshot failed: $filename", e)
        }
    }
}
