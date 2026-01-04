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
import com.felix.face.EngineCallback
import com.felix.face.Scene
import com.felix.face.ActionType
import com.felix.face.FaceCode

class LivenessActivity : AppCompatActivity() {

    private lateinit var livenessEngine: MediaPipeLivenessEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ...

        // 1. 创建引擎实例
        livenessEngine = MediaPipeLivenessEngine(this)

        // 2. 初始化并设置回调
        livenessEngine.init(object : EngineCallback {
            override fun onInit(code: Int) {
                // 初始化完成回调
                if (code == FaceCode.CODE_SUCCESS) {
                    Log.d("FaceAi", "引擎初始化成功")
                    // 初始化成功后，可以开始第一个场景
                    livenessEngine.switchToScene(Scene.FACE_IN_FRAME)
                } else {
                    Log.e("FaceAi", "引擎初始化失败，错误码: $code")
                }
            }

            override fun onProgress(scene: Int, progress: Float) {
                // 场景进度回调，progress 范围 0.0-1.0
                // 可用于更新进度条
                Log.d("FaceAi", "场景 $scene 进度: ${(progress * 100).toInt()}%")
            }

            override fun onTip(scene: Int, action: Int?, code: Int) {
                // 提示回调，用于指导用户调整
                val message = when (code) {
                    FaceCode.CODE_NO_FACE_DETECTED -> "请将人脸对准摄像头"
                    FaceCode.CODE_NOT_IN_FRAME -> "请将人脸放入框内"
                    FaceCode.CODE_FACE_NOT_FRONT -> "请保持正面"
                    FaceCode.CODE_FACE_SIZE_INVALID -> "请调整距离"
                    FaceCode.CODE_FACE_DETECTED -> "检测到人脸"
                    else -> "请按照提示操作"
                }
                runOnUiThread {
                    Toast.makeText(this@LivenessActivity, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCompleted(scene: Int, action: Int?, code: Int) {
                // 场景完成回调（成功或失败）
                when {
                    code == FaceCode.CODE_SUCCESS -> {
                        when (scene) {
                            Scene.FACE_IN_FRAME -> {
                                Log.d("FaceAi", "人脸入框检测成功")
                                // 可以开始下一个场景，例如动作检测
                                livenessEngine.switchToScene(Scene.ACTION, ActionType.BLINK)
                            }
                            Scene.ACTION -> {
                                Log.d("FaceAi", "动作检测成功: $action")
                                // 动作完成，可以进入抓拍场景
                                livenessEngine.switchToScene(Scene.CAPTURE)
                            }
                            Scene.CAPTURE -> {
                                Log.d("FaceAi", "抓拍场景完成")
                            }
                        }
                    }
                    code == FaceCode.CODE_TIMEOUT -> {
                        Log.e("FaceAi", "场景超时: $scene")
                    }
                    else -> {
                        Log.e("FaceAi", "场景失败: $scene, 错误码: $code")
                    }
                }
            }

            override fun onCapture(scene: Int, bitmap: Bitmap) {
                // 抓拍成功回调
                Log.d("FaceAi", "抓拍成功!")
                // 在这里处理抓拍到的 bitmap，例如上传服务器
            }

            // onLandmarks 是可选的，用于自定义检测逻辑
            // 如果返回 true，SDK 会跳过默认检测逻辑
            // override fun onLandmarks(scene: Int, action: Int?, landmarks: List<LandmarkProto.NormalizedLandmark>): Boolean {
            //     return false  // 返回 false 表示使用 SDK 默认检测
            // }
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

通过调用 `switchToScene()` 方法来控制整个活体检测的流程。检测流程通常包括：

1. **人脸入框检测** (`Scene.FACE_IN_FRAME`) - 检测人脸是否在框内
2. **动作检测** (`Scene.ACTION`) - 检测指定动作（眨眼、张嘴、摇头、点头等）
3. **抓拍** (`Scene.CAPTURE`) - 抓拍正面照片

```kotlin
// 在相机启动后，开始第一个场景：人脸入框检测
// 注意：在 onInit() 回调成功后调用
livenessEngine.switchToScene(Scene.FACE_IN_FRAME)

// 当 onCompleted() 回调触发且 scene == Scene.FACE_IN_FRAME 时，开始动作检测
// 例如：要求用户眨眼
livenessEngine.switchToScene(Scene.ACTION, ActionType.BLINK)

// 当第一个动作完成后，可以要求用户做第二个动作
// 例如：要求用户张嘴
livenessEngine.switchToScene(Scene.ACTION, ActionType.MOUTH_OPEN)

// 所有动作完成后，进入抓拍阶段
livenessEngine.switchToScene(Scene.CAPTURE)

// 或者，如果需要入框后立即抓拍，可以使用：
livenessEngine.switchToScene(Scene.FACE_IN_FRAME_CAPTURE)
```

**设置超时时间**（可选）：
```kotlin
// 设置每个场景的超时时间（默认 20 秒）
livenessEngine.setSceneTimeout(30000) // 30 秒
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

#### 初始化与销毁

*   `init(callback: EngineCallback)`: 初始化引擎并设置回调。初始化是异步的，结果通过 `onInit()` 回调返回。
*   `destroy()`: 释放所有资源，**必须调用**。

#### 场景管理

*   `switchToScene(scene: Int, action: Int? = null)`: 切换到指定场景。
    *   `scene`: 场景类型，使用 `Scene` 常量（`Scene.FACE_IN_FRAME`, `Scene.FACE_IN_FRAME_CAPTURE`, `Scene.ACTION`, `Scene.CAPTURE`）
    *   `action`: 动作类型（仅在 `scene == Scene.ACTION` 时必填），使用 `ActionType` 常量
*   `setSceneTimeout(timeout: Long)`: 设置每个场景的超时时间（毫秒），默认 20000ms（20秒）

#### 图像处理

*   `processFrame(imageProxy: ImageProxy)`: **核心方法**。处理从 CameraX 获取的每一帧图像。
*   `processBitmap(bitmap: Bitmap, timestampMs: Long)`: 处理 Bitmap 图像（用于非 CameraX 场景）。

#### 静态方法

*   `setLogEnabled(enabled: Boolean)`: 设置日志开关
*   `isLogEnabled(): Boolean`: 获取日志开关状态
*   `getVersion(): String`: 获取 FaceAI 版本号

### `EngineCallback`

所有回调方法都在主线程中执行，可以直接更新 UI。

*   `onInit(code: Int)`: 初始化完成回调
    *   `code == FaceCode.CODE_SUCCESS`: 初始化成功
    *   其他值：初始化失败，具体错误码见 `FaceCode`
*   `onProgress(scene: Int, progress: Float)`: 场景进度回调
    *   `scene`: 当前场景
    *   `progress`: 进度值 0.0-1.0
*   `onTip(scene: Int, action: Int?, code: Int)`: 提示回调，用于指导用户调整
    *   `scene`: 当前场景
    *   `action`: 动作类型（仅在 ACTION 场景时不为 null）
    *   `code`: 提示码，见 `FaceCode` 常量
*   `onCompleted(scene: Int, action: Int?, code: Int)`: 场景完成回调（成功或失败统一回调）
    *   `scene`: 场景类型
    *   `action`: 动作类型（仅在 ACTION 场景时不为 null）
    *   `code`: 结果码（成功：`FaceCode.CODE_SUCCESS`，失败：对应错误码）
*   `onCapture(scene: Int, bitmap: Bitmap)`: 抓拍成功回调
    *   `scene`: 当前场景
    *   `bitmap`: 抓拍得到的图片
*   `onLandmarks(scene: Int, action: Int?, landmarks: List<LandmarkProto.NormalizedLandmark>): Boolean`: 人脸关键点数据回调（可选，有默认实现）
    *   每帧都会调用，用于自定义检测逻辑
    *   返回 `true` 表示用户已处理，SDK 跳过默认检测；返回 `false` 表示使用 SDK 默认检测

### 常量类

#### `Scene` - 场景类型

*   `Scene.FACE_IN_FRAME = 0`: 人脸入框检测场景（检测到入框后回调，不抓拍）
*   `Scene.FACE_IN_FRAME_CAPTURE = 1`: 人脸入框检测场景，检测到入框后立即抓拍
*   `Scene.ACTION = 2`: 动作检测场景
*   `Scene.CAPTURE = 3`: 直接抓拍场景（需要人脸在框内且正面，连续1秒满足条件后抓拍）

#### `ActionType` - 动作类型

*   `ActionType.BLINK = 0`: 眨眼
*   `ActionType.MOUTH_OPEN = 1`: 张嘴
*   `ActionType.SHAKE_HEAD = 2`: 摇头
*   `ActionType.NOD_HEAD = 3`: 点头

#### `FaceCode` - 错误码

*   **成功**: `FaceCode.CODE_SUCCESS = 0`
*   **超时**: `FaceCode.CODE_TIMEOUT = 502`
*   **不在框内**: `FaceCode.CODE_NOT_IN_FRAME = 1000`
*   **人脸检测相关**:
    *   `FaceCode.CODE_NO_FACE_DETECTED = 2000`: 未检测到人脸
    *   `FaceCode.CODE_FACE_SIZE_INVALID = 2001`: 人脸大小不合适
    *   `FaceCode.CODE_FACE_NOT_FRONT = 2002`: 人脸不是正面
    *   `FaceCode.CODE_FACE_DETECTED = 2003`: 检测到人脸
*   **抓拍相关**: `FaceCode.CODE_CAPTURE_BITMAP_NULL = 4000`: 抓拍时 Bitmap 为 null
*   **系统错误**:
    *   `FaceCode.CODE_JNI_LOAD_FAILED = 9000`: JNI 库加载失败
    *   `FaceCode.CODE_ENGINE_ERROR = 9001`: 引擎内部错误

## 5. 完整使用示例

以下是一个完整的活体检测流程示例：

```kotlin
class LivenessActivity : AppCompatActivity() {
    private lateinit var livenessEngine: MediaPipeLivenessEngine
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liveness)
        
        previewView = findViewById(R.id.previewView)
        
        // 创建引擎
        livenessEngine = MediaPipeLivenessEngine(this)
        
        // 设置超时时间（可选）
        livenessEngine.setSceneTimeout(30000) // 30秒
        
        // 初始化引擎
        livenessEngine.init(createCallback())
        
        // 启动相机
        startCamera()
    }

    private fun createCallback(): EngineCallback {
        return object : EngineCallback {
            override fun onInit(code: Int) {
                if (code == FaceCode.CODE_SUCCESS) {
                    // 初始化成功，开始第一个场景
                    livenessEngine.switchToScene(Scene.FACE_IN_FRAME)
                } else {
                    // 初始化失败
                    runOnUiThread {
                        Toast.makeText(this@LivenessActivity, "初始化失败: $code", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onProgress(scene: Int, progress: Float) {
                // 更新进度条
                runOnUiThread {
                    progressBar.progress = (progress * 100).toInt()
                }
            }

            override fun onTip(scene: Int, action: Int?, code: Int) {
                val message = when (code) {
                    FaceCode.CODE_NO_FACE_DETECTED -> "请将人脸对准摄像头"
                    FaceCode.CODE_NOT_IN_FRAME -> "请将人脸放入框内"
                    FaceCode.CODE_FACE_NOT_FRONT -> "请保持正面"
                    FaceCode.CODE_FACE_SIZE_INVALID -> "请调整距离"
                    FaceCode.CODE_FACE_DETECTED -> "检测到人脸"
                    else -> "请按照提示操作"
                }
                runOnUiThread {
                    tipTextView.text = message
                }
            }

            override fun onCompleted(scene: Int, action: Int?, code: Int) {
                when {
                    code == FaceCode.CODE_SUCCESS -> {
                        when (scene) {
                            Scene.FACE_IN_FRAME -> {
                                // 入框成功，开始动作检测
                                livenessEngine.switchToScene(Scene.ACTION, ActionType.BLINK)
                            }
                            Scene.ACTION -> {
                                // 动作完成，可以继续下一个动作或进入抓拍
                                if (action == ActionType.BLINK) {
                                    // 第一个动作完成，开始第二个动作
                                    livenessEngine.switchToScene(Scene.ACTION, ActionType.MOUTH_OPEN)
                                } else {
                                    // 所有动作完成，进入抓拍
                                    livenessEngine.switchToScene(Scene.CAPTURE)
                                }
                            }
                            Scene.CAPTURE -> {
                                // 抓拍场景完成（注意：实际抓拍结果在 onCapture 回调中）
                            }
                        }
                    }
                    code == FaceCode.CODE_TIMEOUT -> {
                        runOnUiThread {
                            Toast.makeText(this@LivenessActivity, "操作超时，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        runOnUiThread {
                            Toast.makeText(this@LivenessActivity, "操作失败: $code", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            override fun onCapture(scene: Int, bitmap: Bitmap) {
                // 抓拍成功，处理图片
                runOnUiThread {
                    // 例如：上传到服务器
                    uploadImage(bitmap)
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        livenessEngine.processFrame(imageProxy)
                    }
                }
            
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("FaceAi", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        livenessEngine.destroy()
    }
}
```

## 6. 注意事项

1. **初始化是异步的**：`init()` 方法是异步的，必须等待 `onInit()` 回调成功后才能调用 `switchToScene()`。

2. **场景切换**：必须在当前场景完成（`onCompleted()` 回调）后才能切换到下一个场景。

3. **ACTION 场景**：切换到 `Scene.ACTION` 时，`action` 参数不能为 `null`。

4. **资源释放**：在 Activity/Fragment 销毁时，务必调用 `destroy()` 释放资源。

5. **线程安全**：所有回调方法都在主线程执行，可以直接更新 UI。

6. **日志控制**：可以通过 `MediaPipeLivenessEngine.setLogEnabled(true)` 开启日志，方便调试。

7. **版本查询**：可以通过 `MediaPipeLivenessEngine.getVersion()` 获取当前库版本。

8. **自定义检测**：如果需要自定义检测逻辑，可以实现 `onLandmarks()` 方法，返回 `true` 以跳过 SDK 默认检测。
