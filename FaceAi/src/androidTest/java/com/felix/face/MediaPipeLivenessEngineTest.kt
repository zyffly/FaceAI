package com.felix.face

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        var initSuccess = false

        val callback = object : EngineCallback {
            override fun onInit(code: Int) {
                if (code == FaceCode.CODE_SUCCESS) {
                    initSuccess = true
                } else {
                    fail("Engine failed to initialize with code: $code")
                }
                latch.countDown()
            }

            override fun onProgress(scene: Int, progress: Float) {}
            override fun onTip(scene: Int, action: Int?, code: Int) {}
            override fun onCompleted(scene: Int, action: Int?, code: Int) {}
            override fun onCapture(scene: Int, bitmap: Bitmap) {}
        }

        livenessEngine.init(callback)
        assertNotNull("LivenessEngine should not be null after creation", livenessEngine)
        
        // Wait for initialization to complete
        latch.await(5, TimeUnit.SECONDS)
        assertTrue("Engine should initialize successfully", initSuccess)
    }
    
    /**
     * Test processing a null or empty frame to ensure no crashes.
     */
    @Test
    fun testNullFrameProcessing() {
        val initLatch = CountDownLatch(1)
        var initSuccess = false

        livenessEngine.init(object : EngineCallback {
            override fun onInit(code: Int) {
                if (code == FaceCode.CODE_SUCCESS) {
                    initSuccess = true
                }
                initLatch.countDown()
            }

            override fun onProgress(scene: Int, progress: Float) {}
            override fun onTip(scene: Int, action: Int?, code: Int) {}
            override fun onCompleted(scene: Int, action: Int?, code: Int) {}
            override fun onCapture(scene: Int, bitmap: Bitmap) {}
        })

        // Wait for initialization
        initLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Engine should initialize successfully", initSuccess)

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
