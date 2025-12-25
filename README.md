# FaceAi 活体检测组件库接入文档

`FaceAi` 是一个基于 MediaPipe Face Mesh 实现的 Android 端活体检测组件库。它提供了人脸入框检测、眨眼、张嘴、摇头、点头等一系列原子能力，帮助您快速构建活体认证流程。

## 1. 环境与依赖

在接入本组件库前，请确保您的主工程（宿主 App）满足以下环境要求：

*   **Kotlin 版本**: `1.8.10` 或更高版本。
*   **minSdk**: `24` 或更高。
*   **核心依赖**:
    *   `androidx.core:core-ktx:1.8.0` 或更高版本。
    *   `androidx.camera:camera-core:1.4.0-alpha04` 或更高版本 (为保证兼容性，建议使用 `1.4.0` 系列)。

> **注意**: 本库已内置 `com.google.mediapipe:solution-core:0.10.9` 和 `com.google.mediapipe:facemesh:0.10.9`。Gradle 会自动处理版本冲突，通常会选择最高版本。

## 2. 安装与配置

### 2.1 添加 AAR 文件

1.  将 `FaceAi-release.aar` 文件复制到您主工程的 `libs` 目录下 (如果 `libs` 目录不存在，请新建一个)。
2.  在主工程的 `build.gradle` (Module 级别) 的 `dependencies` 中添加以下依赖：

    ```groovy
    dependencies {
        // ... other dependencies
        implementation files('libs/FaceAi-release.aar')
    }
    ```

### 2.2 添加 CameraX 依赖

您需要手动添加 CameraX 的相关依赖来实现相机预览和图像分析。

```groovy
dependencies {
    // ...

    // CameraX 核心库 (组件库已依赖，但建议显式声明)
    implementation "androidx.camera:camera-core:1.4.0-alpha04"
    // CameraX 用于预览
    implementation "androidx.camera:camera-view:1.4.0-alpha04"
    // CameraX 生命周期支持
    implementation "androidx.camera:camera-lifecycle:1.4.0-alpha04"
}
```

## 3. 快速开始

### 3.1 核心 API 概览

您主要通过 `com.felix.face.MediaPipeLivenessEngine` 类与本组件库交互。

### 3.2 步骤 1: 初始化引擎

在您的 Activity 或 Fragment 中，创建 `MediaPipeLivenessEngine` 实例，并实现 `EngineCallback` 接口来接收检测结果。

```kotlin
import com.felix.face.MediaPipeLivenessEngine
import com.felix.face.MediaPipeLivenessEngine.*

class LivenessActivity : AppCompatActivity() {

    private lateinit var livenessEngine: MediaPipeLivenessEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ...

        // 1. 创建引擎实例
        livenessEngine = MediaPipeLivenessEngine(this)

        // 2. 初始化并设置回调
        livenessEngine.init(object : EngineCallback {
            override fun onFaceInFrame() {
                // 人脸已稳定在框内，可以开始下一步动作
                // 例如: livenessEngine.setExpectedAction(ActionType.BLINK)
                Log.d("FaceAi", "人脸已在框内")
            }

            override fun onActionCompleted(step: Step) {
                // 指定的动作已完成
                // 例如: 如果是第一个动作完成，可以开始第二个动作
                Log.d("FaceAi", "动作完成: $step")
            }

            override fun onCaptureSuccess(bitmap: Bitmap) {
                // 活体检测通过，成功抓拍到正面照片
                Log.d("FaceAi", "抓拍成功!")
                // 在这里处理抓拍到的 bitmap，例如上传服务器
            }

            override fun onMessage(message: String) {
                // 接收提示信息，用于UI展示
                // 例如: "请将头像放入框内", "离远点", "请保持正面头像"
                runOnUiThread {
                    Toast.makeText(this@LivenessActivity, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onTimeout(step: Step) {
                // 当前步骤超时
                Log.e("FaceAi", "步骤超时: $step")
            }
            
            // 其他回调...
            override fun onStepChanged(step: Step, message: String) {}
            override fun onProgress(progress: Float) {}
            override fun onActionRequired(action: String) {}
            override fun onFailure(message: String) {}
        })
    }
}
```

### 3.3 步骤 2: 集成 CameraX

您需要配置 CameraX，将 `ImageAnalysis` use case 的输出流对接到 `livenessEngine`。

```kotlin
private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        // 预览
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider) // previewView 是你的 PreviewView 控件
        }

        // 图像分析
        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    // 将相机帧送入活体检测引擎
                    livenessEngine.processFrame(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e("FaceAi", "Use case binding failed", exc)
        }
    }, ContextCompat.getMainExecutor(this))
}
```

### 3.4 步骤 3: 管理检测流程

通过调用引擎的方法来控制整个活体检测的流程。

```kotlin
// 在相机启动后，开始检测
livenessEngine.start() // 默认进入 FACE_IN_FRAME 步骤

// 当 onFaceInFrame() 回调被触发时，可以要求用户做第一个动作
// livenessEngine.setExpectedAction(ActionType.BLINK)

// 当 onActionCompleted() 回调被触发时，可以要求用户做第二个动作
// livenessEngine.setExpectedAction(ActionType.MOUTH_OPEN)

// 所有动作完成后，进入抓拍阶段
// livenessEngine.setCaptureStep()
```

### 3.5 步骤 4: 释放资源

在 Activity 或 Fragment 销毁时，务必调用 `destroy()` 方法释放引擎和底层C++资源。

```kotlin
override fun onDestroy() {
    super.onDestroy()
    livenessEngine.destroy()
}
```

## 4. API 详解

### `MediaPipeLivenessEngine`

*   `init(callback: EngineCallback)`: 初始化引擎并设置回调。
*   `processFrame(imageProxy: ImageProxy)`: **核心方法**。处理从 CameraX 获取的每一帧图像。
*   `start()`: 开始检测流程，进入 `FACE_IN_FRAME` 状态。
*   `setExpectedAction(action: ActionType)`: 设置期望用户完成的动作。
*   `setCaptureStep()`: 进入最终的抓拍正面照阶段。
*   `destroy()`: 释放所有资源，**必须调用**。

### `EngineCallback`

*   `onFaceInFrame()`: 人脸稳定在框内时回调。
*   `onActionCompleted(step: Step)`: 期望的动作完成时回调。
*   `onCaptureSuccess(bitmap: Bitmap)`: 活体检测成功并通过，返回抓拍的正面 `Bitmap`。
*   `onMessage(message: String)`: 返回需要展示给用户的提示语。
*   `onTimeout(step: Step)`: 当前步骤超时回调。
*   ... (其他回调)

### Enums

*   `Step`: `FACE_IN_FRAME`, `ACTION1`, `ACTION2`, `CAPTURE`
*   `ActionType`: `BLINK`, `MOUTH_OPEN`, `SHAKE_HEAD`, `NOD_HEAD`
