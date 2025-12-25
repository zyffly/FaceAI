package com.felix.face

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.felix.face.MediaPipeLivenessEngine.EngineCallback
import com.felix.face.MediaPipeLivenessEngine.Step
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented test for MediaPipeLivenessEngine, which will execute on an Android device.
 *
 * This test verifies the basic initialization, destruction, and callback mechanism of the engine.
 */
@RunWith(AndroidJUnit4::class)
class MediaPipeLivenessEngineTest {

    private lateinit var context: Context
    private lateinit var livenessEngine: MediaPipeLivenessEngine

    @Before
    fun setup() {
        // Get context from the instrumentation registry
        context = InstrumentationRegistry.getInstrumentation().targetContext
        livenessEngine = MediaPipeLivenessEngine(context)
    }

    @After
    fun tearDown() {
        livenessEngine.destroy()
    }

    /**
     * Tests if the engine can be initialized and destroyed without crashing.
     * This is a basic sanity check to ensure native libraries are loaded correctly.
     */
    @Test
    fun testEngineInitializationAndDestruction() {
        val latch = CountDownLatch(1)

        val callback = object : EngineCallback {
            override fun onStepChanged(step: Step, message: String) {}
            override fun onProgress(progress: Float) {}
            override fun onActionRequired(action: String) {}
            override fun onFaceInFrame() {}
            override fun onActionCompleted(step: Step) {}
            override fun onCaptureSuccess(bitmap: Bitmap) {}
            override fun onTimeout(step: Step) {}
            override fun onFailure(message: String) {
                fail("Engine failed to initialize with message: $message")
            }
            override fun onMessage(message: String) {}
        }

        livenessEngine.init(callback)
        assertNotNull("LivenessEngine should not be null after creation", livenessEngine)
        
        // A simple way to ensure init doesn't crash immediately
        // A more complex test would involve mocking MediaPipe results
        livenessEngine.start()
        latch.await(500, TimeUnit.MILLISECONDS) // Give it a moment
    }
    
    /**
     * Test processing a null or empty frame to ensure no crashes.
     */
    @Test
    fun testNullFrameProcessing() {
        livenessEngine.init(object : EngineCallback {
            override fun onStepChanged(step: Step, message: String) {}
            override fun onProgress(progress: Float) {}
            override fun onActionRequired(action: String) {}
            override fun onFaceInFrame() {}
            override fun onActionCompleted(step: Step) {}
            override fun onCaptureSuccess(bitmap: Bitmap) {}
            override fun onTimeout(step: Step) {}
            override fun onFailure(message: String) {}
            override fun onMessage(message: String) {}
        })

        try {
            // This test is basic, it does not mock a real ImageProxy
            // It just checks for null pointer exceptions with fake inputs
            val emptyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            livenessEngine.processBitmap(emptyBitmap, System.currentTimeMillis())
        } catch (e: Exception) {
            fail("Processing an empty bitmap should not crash the engine. Error: ${e.message}")
        }
    }
}
