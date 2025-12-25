package com.felix.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.formats.proto.LandmarkProto
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import kotlin.math.*
import androidx.core.graphics.createBitmap

/**
 * 使用 MediaPipe Face Mesh 的自定义活体检测引擎
 */
class MediaPipeLivenessEngine(private val context: Context) {

    companion object {
        // ========== 成功 ==========
        const val CODE_SUCCESS = 0
        
        // ========== 超时 ==========
        const val CODE_TIMEOUT = 502                    // 场景超时
        
        // ========== 不在框内（统一使用） ==========
        const val CODE_NOT_IN_FRAME = 1002              // 检测对象不在框内
        
        // ========== 人脸检测相关 (1000-1999) ==========
        const val CODE_NO_FACE_DETECTED = 1001          // 未检测到人脸
        const val CODE_FACE_SIZE_INVALID = 1003         // 人脸大小不合适（太大或太小）
        const val CODE_FACE_NOT_FRONT = 1004            // 人脸不是正面
        
        // ========== 动作检测相关 (2000-2999) ==========
        const val CODE_ACTION_NOT_COMPLETED = 2001      // 动作未完成（超时）
        const val CODE_ACTION_TYPE_NULL = 2002           // ACTION场景下动作类型为空
        
        // ========== 抓拍相关 (3000-3999) ==========
        const val CODE_CAPTURE_BITMAP_NULL = 3002       // 抓拍时Bitmap为null
        
        // ========== 系统错误 (4000-4999) ==========
        const val CODE_INIT_FAILED = 4001               // 初始化失败
        const val CODE_JNI_LOAD_FAILED = 4002           // JNI库加载失败
        const val CODE_CAMERA_INIT_FAILED = 4003        // 相机初始化失败
        const val CODE_ENGINE_ERROR = 4004              // 引擎内部错误
    }

    private var faceMesh: FaceMesh? = null
    private var currentScene: Scene? = null
    private var sceneStartTime = 0L
    private var sceneTimeout = 20000L  // 默认20秒，可通过setSceneTimeout()方法设置
    private var faceInFrameCount = 0
    private var faceInFrameStartTime = 0L  // 人脸开始连续在框内的时间戳
    private val faceInFrameRequiredDuration = 2000L  // 需要连续2秒在框内
    private var captureReadyStartTime = 0L  // 抓拍准备开始的时间戳（满足条件时开始计时）
    private val captureReadyRequiredDuration = 1000L  // 需要连续1秒在框内且正面才抓拍

    // YuvToRgbConverter 实例
    private val yuvToRgbConverter = YuvToRgbConverter(context)
    // 缓存 Bitmap 以复用内存
    private var analysisBitmap: Bitmap? = null

    /**
     * 场景枚举
     */
    enum class Scene {
        FACE_IN_FRAME,           // 人脸入框检测场景（检测到入框后回调，不抓拍）
        FACE_IN_FRAME_CAPTURE,   // 人脸入框检测场景，检测到入框后立即抓拍
        ACTION,                   // 动作检测场景
        CAPTURE                   // 直接抓拍场景（不检测入框，直接抓拍）
    }

    /**
     * 引擎回调接口
     */
    interface EngineCallback {
        /**
         * 初始化成功回调
         */
        fun onInitSuccess()
        
        /**
         * 进度回调
         * @param scene 当前场景
         * @param progress 进度值 0.0-1.0
         */
        fun onProgress(scene: Scene, progress: Float)
        
        /**
         * 人脸关键点数据回调（每帧都会调用，用于自定义检测逻辑）
         * @param scene 当前场景
         * @param action 动作类型（仅在ACTION场景时不为null）
         * @param landmarks 人脸关键点列表（MediaPipe Face Mesh的468个关键点）
         * @return true 表示用户已处理，SDK跳过默认检测；false 表示用户未处理，SDK执行默认检测
         */
        fun onLandmarks(
            scene: Scene, 
            action: ActionType?, 
            landmarks: List<LandmarkProto.NormalizedLandmark>
        ): Boolean {
            // 默认返回false，表示未处理，SDK执行默认检测
            return false
        }
        
        /**
         * 检测过程中的提示回调（用于指导用户调整）
         * @param scene 当前场景
         * @param action 动作类型（仅在ACTION场景时不为null）
         * @param code 提示码，集成者根据code决定显示什么文案
         */
        fun onTip(scene: Scene, action: ActionType?, code: Int)
        
        /**
         * 场景成功回调
         * @param scene 场景类型
         * @param action 动作类型（仅在ACTION场景时不为null）
         */
        fun onSuccess(scene: Scene, action: ActionType?)
        
        /**
         * 场景失败回调（最终失败）
         * @param scene 场景类型
         * @param action 动作类型（仅在ACTION场景时不为null）
         * @param code 错误码
         */
        fun onFailure(scene: Scene, action: ActionType?, code: Int)
        
        /**
         * 抓拍成功回调
         * @param scene 当前场景
         * @param bitmap 抓拍得到的图片
         */
        fun onCaptureSuccess(scene: Scene, bitmap: Bitmap)
    }

    private var callback: EngineCallback? = null

    // 动作检测状态
    private var blinkCount = 0
    private var blinkState = BlinkState.OPEN  // 眨眼状态：OPEN(睁眼) -> CLOSING(闭眼中) -> CLOSED(闭眼) -> OPENING(睁眼中)
    private var mouthOpenCount = 0
    private var headShakeCount = 0
    private var headShakeState = HeadShakeState.CENTER  // 摇头状态
    private var headShakeMaxYaw = 0f  // 记录摆动过程中的最大yaw绝对值
    private var headShakeMinYaw = 0f  // 记录摆动过程中的最小yaw值（实际值，可为负）
    private var headShakeMaxYawValue = 0f  // 记录摆动过程中的最大yaw值（实际值，可为正）
    private var headShakeInitialYaw = 0f  // 记录开始摆动时的初始yaw值（正面时的yaw值）
    private var headShakeStartedFromCenter = false  // 是否从正面开始
    private var expectedAction: ActionType? = null
    private var nodBaseDistance = 0f  // 点头检测的基准距离（用于检测变化）
    
    // 眨眼状态枚举
    private enum class BlinkState {
        OPEN,      // 睁眼状态
        CLOSING,   // 正在闭眼
        CLOSED,    // 闭眼状态
        OPENING    // 正在睁眼
    }
    
    // 摇头状态枚举
    private enum class HeadShakeState {
        CENTER,   // 中间状态
        LEFT,     // 左侧
        RIGHT     // 右侧
    }

    enum class ActionType {
        BLINK,      // 眨眼
        MOUTH_OPEN, // 张嘴
        SHAKE_HEAD, // 摇头
        NOD_HEAD    // 点头
    }

    /**
     * 初始化 MediaPipe Face Mesh
     */
    fun init(callback: EngineCallback) {
        this.callback = callback

        val options = FaceMeshOptions.builder()
            .setStaticImageMode(false)  // 视频流模式
            .setMaxNumFaces(1)  // 只检测一张脸
            .setRefineLandmarks(true)  // 细化关键点
            .setMinDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()

        faceMesh = FaceMesh(context, options).apply {
            setResultListener { result ->
                // 只有在场景已设置时才处理结果（避免在switchToScene()之前处理导致超时）
                if (currentScene != null) {
                processFaceMeshResult(result)
            }
        }
        }
        Log.d("LivenessEngine", "MediaPipe FaceMesh初始化完成")
        callback.onInitSuccess()
    }

    /**
     * 处理 MediaPipe 检测结果
     */
    private fun processFaceMeshResult(result: FaceMeshResult) {
        // 统一在开始处检查场景，如果没有场景，直接返回，不调用任何回调
        val scene = currentScene ?: return
        
        // 无论是否检测到人脸，都需要更新进度和检查超时
        if (result.multiFaceLandmarks().isEmpty()) {
            Log.d("LivenessEngine", "未检测到人脸")
            callback?.onTip(scene, expectedAction, CODE_NO_FACE_DETECTED)
            // 即使未检测到人脸，也要更新进度和检查超时
            updateProgress(scene)
            checkTimeout()
            return
        }

        val landmarks = result.multiFaceLandmarks()[0].landmarkList
        Log.d("LivenessEngine", "检测到人脸，当前场景: $scene")
        
        // 调用 onLandmarks 回调，判断用户是否自己处理了
        val isHandled = callback?.onLandmarks(scene, expectedAction, landmarks) ?: false
        
        // 如果用户已处理，跳过SDK的默认检测逻辑
        if (isHandled) {
            // 仍然需要更新进度和检查超时
            updateProgress(scene)
            checkTimeout()
            return
        }
        
        // 如果用户未处理，执行SDK的默认检测逻辑
        when (scene) {
            Scene.FACE_IN_FRAME -> {
                // 只检测入框，不入框后抓拍
                checkFaceInFrame(scene, landmarks)
                updateProgress(scene)
            }
            Scene.FACE_IN_FRAME_CAPTURE -> {
                // 检测入框，入框后立即抓拍
                if (checkFaceInFrame(scene, landmarks)) {
                    // 入框后立即抓拍
                    checkCapture(scene, landmarks)
                }
                updateProgress(scene)
            }
            Scene.ACTION -> {
                checkAction(scene, landmarks)
                updateProgress(scene)
            }
            Scene.CAPTURE -> {
                // 直接抓拍，不检测入框
                checkCapture(scene, landmarks)
                updateProgress(scene)
            }
        }

        // 检查超时
        checkTimeout()
    }

    /**
     * 更新进度
     */
    private fun updateProgress(scene: Scene) {
        val elapsed = System.currentTimeMillis() - sceneStartTime
        val progress = (elapsed / sceneTimeout.toFloat()).coerceIn(0f, 1f)
        callback?.onProgress(scene, progress)
    }

    /**
     * 检测人脸是否在框内（需要连续2秒正面在框内）
     * @param scene 当前场景
     * @param landmarks 人脸关键点
     * @return true 如果人脸已入框，false 如果还在检测中
     */
    private fun checkFaceInFrame(scene: Scene, landmarks: List<LandmarkProto.NormalizedLandmark>): Boolean {
        // 获取人脸边界框
        val faceRect = getFaceRect(landmarks)
        val faceSize = getFaceSize(faceRect)
        val faceCenter = getFaceCenter(faceRect)

        // 判断是否在框内（框的中心在屏幕的 40% 高度，宽度为屏幕的 42%）
        // 注意：这里需要与UI绘制的位置保持一致
        val screenWidth = 1.0f  // 归一化坐标
        val screenHeight = 1.0f
        val frameCenterX = 0.5f  // 屏幕中心
        val frameCenterY = 0.4f  // 屏幕40%高度（与UI绘制位置一致）
        val frameRadius = 0.42f  // 屏幕宽度的42%（与UI绘制一致）

        val distanceFromCenter = sqrt(
            (faceCenter.x - frameCenterX).pow(2) +
                    (faceCenter.y - frameCenterY).pow(2)
        )

        // 放宽检测条件，使识别更容易
        val isInFrame = distanceFromCenter < frameRadius * 0.95f  // 从0.8放宽到0.95
        val isSizeFit = faceSize in 0.1f..0.8f  // 大幅放宽大小范围，适应不同距离
        
        // 检查四个角点是否都在框内（防止人脸一半在框外）
        val cornerMarginRatio = 0.9f
        val corners = listOf(
            Point(faceRect.left, faceRect.top),
            Point(faceRect.left, faceRect.bottom),
            Point(faceRect.right, faceRect.top),
            Point(faceRect.right, faceRect.bottom)
        )
        val areCornersInside = corners.all {
            val dist = sqrt((it.x - frameCenterX).pow(2) + (it.y - frameCenterY).pow(2))
            dist <= frameRadius * cornerMarginRatio
        }
        
        val horizontalMargin = 0.02f
        val verticalMargin = 0.03f
        val isWithinBounds =
            faceRect.left >= frameCenterX - frameRadius + horizontalMargin &&
            faceRect.right <= frameCenterX + frameRadius - horizontalMargin &&
            faceRect.top >= frameCenterY - frameRadius + verticalMargin &&
            faceRect.bottom <= frameCenterY + frameRadius - verticalMargin
        
        // 检查是否正面（通过头部姿态）
        // 注意：由于z坐标可能不准确，暂时使用更宽松的条件或简化判断
        val headPose = estimateHeadPose(landmarks)
        // 如果角度计算异常（绝对值过大），可能是z坐标问题，暂时放宽或忽略
        // 先检查角度是否在合理范围内，如果不在，可能是计算问题，暂时认为正面
        val yawAbs = abs(headPose.yaw)
        val pitchAbs = abs(headPose.pitch)
        val isFrontFace = if (yawAbs > 90f || pitchAbs > 90f) {
            // 角度异常，可能是z坐标问题，暂时认为正面（后续可以优化）
            true
        } else {
            yawAbs < 30f && pitchAbs < 30f  // 正常范围内的角度检查
        }

        val currentTime = System.currentTimeMillis()
        
        // 输出详细的检测信息（每500ms输出一次，避免日志过多）
        val now = System.currentTimeMillis()
        if (now % 500 < 50) {  // 大约每500ms输出一次
            Log.d("LivenessEngine", "检测条件详情: distance=$distanceFromCenter (阈值=${frameRadius * 0.95f}), isInFrame=$isInFrame, " +
                    "faceSize=$faceSize (范围0.25-0.6), isSizeFit=$isSizeFit, yaw=${headPose.yaw}, pitch=${headPose.pitch}, isFrontFace=$isFrontFace, " +
                    "faceCenter=(${faceCenter.x}, ${faceCenter.y}), frameCenter=($frameCenterX, $frameCenterY)")
        }
        
        if (isInFrame && isSizeFit && areCornersInside && isWithinBounds && isFrontFace) {
            // 人脸在框内且正面
            if (faceInFrameStartTime == 0L) {
                // 开始计时
                faceInFrameStartTime = currentTime
                Log.d("LivenessEngine", "开始计时：人脸在框内")
            } else {
                // 检查是否连续2秒
                val duration = currentTime - faceInFrameStartTime
                val remainingTime = (faceInFrameRequiredDuration - duration) / 1000f
                if (duration >= faceInFrameRequiredDuration) {
                    Log.d("LivenessEngine", "人脸识别成功：连续${duration}ms在框内")
                    // 只在 FACE_IN_FRAME 场景时回调，FACE_IN_FRAME_CAPTURE 场景不回调
                    if (scene == Scene.FACE_IN_FRAME) {
                        notifySceneSuccess(scene, null)
                    }
                    faceInFrameStartTime = 0L
                    return true  // 返回true表示已入框
                } else {
                    // 提示保持（检测中）
                    callback?.onTip(scene, null, CODE_NOT_IN_FRAME)
                }
            }
        } else {
            // 不在框内或不是正面，重置计时
            if (faceInFrameStartTime != 0L) {
                Log.d("LivenessEngine", "重置计时：isInFrame=$isInFrame, isSizeFit=$isSizeFit, isFrontFace=$isFrontFace, distance=$distanceFromCenter, size=$faceSize, yaw=${headPose.yaw}, pitch=${headPose.pitch}")
                faceInFrameStartTime = 0L
            }
            when {
                !isInFrame -> callback?.onTip(scene, null, CODE_NOT_IN_FRAME)
                !isFrontFace -> callback?.onTip(scene, null, CODE_FACE_NOT_FRONT)
                faceSize > 0.8f || faceSize < 0.1f -> callback?.onTip(scene, null, CODE_FACE_SIZE_INVALID)
            }
        }
        return false  // 还在检测中，未入框
    }

    /**
     * 第二步/第三步：检测动作（必须在框内才检测）
     * @param scene 当前场景
     * @param landmarks 人脸关键点
     */
    private fun checkAction(scene: Scene, landmarks: List<LandmarkProto.NormalizedLandmark>) {
        // 如果没有设置期望的动作，直接返回
        if (expectedAction == null) {
            return
        }
        
        // 先检查是否在框内
        val faceRect = getFaceRect(landmarks)
        val faceSize = getFaceSize(faceRect)
        val faceCenter = getFaceCenter(faceRect)
        
        val frameCenterX = 0.5f
        val frameCenterY = 0.4f  // 与UI绘制位置一致
        val frameRadius = 0.42f  // 与UI绘制一致
        
        val distanceFromCenter = sqrt(
            (faceCenter.x - frameCenterX).pow(2) +
                    (faceCenter.y - frameCenterY).pow(2)
        )
        
        // 使用与checkFaceInFrame相同的宽松条件
        val isInFrame = distanceFromCenter < frameRadius * 0.95f
        val isSizeFit = faceSize in 0.1f..0.8f
        
        // 检查四个角点是否都在框内（防止人脸一半在框外）
        val cornerMarginRatio = 0.9f
        val corners = listOf(
            Point(faceRect.left, faceRect.top),
            Point(faceRect.left, faceRect.bottom),
            Point(faceRect.right, faceRect.top),
            Point(faceRect.right, faceRect.bottom)
        )
        val areCornersInside = corners.all {
            val dist = sqrt((it.x - frameCenterX).pow(2) + (it.y - frameCenterY).pow(2))
            dist <= frameRadius * cornerMarginRatio
        }
        
        val horizontalMargin = 0.02f
        val verticalMargin = 0.03f
        val isWithinBounds =
            faceRect.left >= frameCenterX - frameRadius + horizontalMargin &&
            faceRect.right <= frameCenterX + frameRadius - horizontalMargin &&
            faceRect.top >= frameCenterY - frameRadius + verticalMargin &&
            faceRect.bottom <= frameCenterY + frameRadius - verticalMargin
        
        if (!isInFrame || !isSizeFit || !areCornersInside || !isWithinBounds) {
            // 不在框内，提示用户
            callback?.onTip(scene, expectedAction, CODE_NOT_IN_FRAME)
            return
        }
        
        // 在框内，检测动作
        when (expectedAction) {
            ActionType.BLINK -> {
                val ear = calculateEAR(landmarks)
                val now = System.currentTimeMillis()
                
                // 状态机：检测完整的眨眼周期
                when (blinkState) {
                    BlinkState.OPEN -> {
                        // 睁眼状态，等待闭眼
                        if (ear < 0.20f) {
                            // 检测到闭眼，进入闭眼中状态
                            blinkState = BlinkState.CLOSING
                            blinkCount = 1
                            Log.d("LivenessEngine", "眨眼检测: 开始闭眼，EAR=$ear, 状态: OPEN -> CLOSING")
                        }
                    }
                    BlinkState.CLOSING -> {
                        // 闭眼中，继续检测闭眼
                        if (ear < 0.20f) {
                            blinkCount++
                            Log.d("LivenessEngine", "眨眼检测: 闭眼中，EAR=$ear, count=$blinkCount")
                            // 折中方案：如果EAR非常低（<0.15），1帧即可；否则需要2帧（防止误判）
                            if (blinkCount >= 1 && ear < 0.15f) {
                                // EAR非常低，确认是闭眼，1帧即可（支持快眨眼）
                                blinkState = BlinkState.CLOSED
                                blinkCount = 0
                                Log.d("LivenessEngine", "眨眼检测: 已闭眼（快速），EAR=$ear, 状态: CLOSING -> CLOSED")
                            } else if (blinkCount >= 2) {
                                // EAR在0.15-0.20之间，需要2帧确认（防止误判）
                                blinkState = BlinkState.CLOSED
                                blinkCount = 0
                                Log.d("LivenessEngine", "眨眼检测: 已闭眼，EAR=$ear, 状态: CLOSING -> CLOSED, 等待睁眼")
                            }
                        } else {
                            // 闭眼中断，回到睁眼状态
                            if (ear >= 0.25f) {
                                blinkState = BlinkState.OPEN
                                blinkCount = 0
                                Log.d("LivenessEngine", "眨眼检测: 闭眼中断，EAR=$ear（>=0.25），状态: CLOSING -> OPEN")
                            } else {
                                // EAR在0.20-0.25之间时，保持CLOSING状态，允许波动
                                Log.d("LivenessEngine", "眨眼检测: 闭眼中波动，EAR=$ear（0.20-0.25之间），保持CLOSING状态，count=$blinkCount")
                            }
                        }
                    }
                    BlinkState.CLOSED -> {
                        // 闭眼状态，等待睁眼
                        if (ear >= 0.18f) {
                            // 检测到睁眼，进入睁眼中状态
                            blinkState = BlinkState.OPENING
                            blinkCount = 1
                            Log.d("LivenessEngine", "眨眼检测: 开始睁眼，EAR=$ear, 状态: CLOSED -> OPENING")
                        } else {
                            // 每500ms输出一次闭眼状态的日志
                            if (now % 500 < 50) {
                                Log.d("LivenessEngine", "眨眼检测: 保持闭眼状态，EAR=$ear, 等待睁眼（EAR>=0.18）")
                            }
                        }
                    }
                    BlinkState.OPENING -> {
                        // 睁眼中，继续检测睁眼
                        if (ear >= 0.18f) {
                            blinkCount++
                            Log.d("LivenessEngine", "眨眼检测: 睁眼中，EAR=$ear, count=$blinkCount")
                            // 折中方案：如果EAR较高（>=0.25），1帧即可；否则需要2帧
                            if (blinkCount >= 1 && ear >= 0.25f) {
                                // EAR较高，确认是睁眼，1帧即可（支持快眨眼）
                                Log.d("LivenessEngine", "眨眼动作完成（快速），EAR=$ear, 状态: OPENING -> OPEN")
                                blinkState = BlinkState.OPEN
                                blinkCount = 0
                                // 清除当前动作，防止重复检测
                                val completedAction = expectedAction
                                expectedAction = null
                                completedAction?.let { notifySceneSuccess(scene, it) }
                            } else if (blinkCount >= 2) {
                                // EAR在0.18-0.25之间，需要2帧确认（防止误判）
                                Log.d("LivenessEngine", "眨眼动作完成（自定义检测），EAR=$ear, 状态: OPENING -> OPEN, 完整周期完成")
                                blinkState = BlinkState.OPEN
                                blinkCount = 0
                                // 清除当前动作，防止重复检测
                                val completedAction = expectedAction
                                expectedAction = null
                                completedAction?.let { notifySceneSuccess(scene, it) }
                            }
                        } else {
                            // 睁眼中断判断：更宽松的条件
                            if (ear < 0.15f) {
                                // 只有EAR非常低时才中断（可能是真的又闭眼了）
                                blinkState = BlinkState.CLOSED
                                blinkCount = 0
                                Log.d("LivenessEngine", "眨眼检测: 睁眼中断，EAR=$ear（<0.15），状态: OPENING -> CLOSED")
                            } else if (ear < 0.18f && blinkCount >= 1) {
                                // EAR在0.15-0.18之间，且已经检测到至少1帧睁眼，允许波动，不中断
                                Log.d("LivenessEngine", "眨眼检测: 睁眼中波动，EAR=$ear（0.15-0.18之间），已检测到${blinkCount}帧睁眼，保持OPENING状态")
                            } else {
                                // EAR在0.15-0.18之间，但count=0，可能是刚开始睁眼，允许波动
                                Log.d("LivenessEngine", "眨眼检测: 睁眼中波动，EAR=$ear（0.15-0.18之间），保持OPENING状态，count=$blinkCount")
                            }
                        }
                    }
                }
            }
            ActionType.MOUTH_OPEN -> {
                val mar = calculateMAR(landmarks)
                val now = System.currentTimeMillis()
                
                // 大幅降低阈值：MAR > 0.38 表示嘴巴张开（轻微张嘴即可通过）
                // 正常闭嘴MAR约0.2-0.3，稍微张嘴MAR就会增加到0.38以上
                if (mar > 0.38f) {
                    mouthOpenCount++
                    Log.d("LivenessEngine", "张嘴检测: MAR=$mar（>0.38，张嘴中）, count=$mouthOpenCount/2")
                    // 减少所需帧数：从3帧降到2帧（约0.07秒@30fps），让检测更灵敏
                    if (mouthOpenCount >= 2) {
                        Log.d("LivenessEngine", "张嘴动作完成（自定义检测），MAR=$mar, count=$mouthOpenCount, 状态: 完成")
                        // 清除当前动作，防止重复检测
                        val completedAction = expectedAction
                        expectedAction = null
                        mouthOpenCount = 0
                        completedAction?.let { notifySceneSuccess(scene, it) }
                    }
                } else {
                    // 允许一定波动：MAR在0.35-0.38之间时，如果已经有计数，不立即重置
                    if (mar >= 0.35f && mouthOpenCount > 0) {
                        // 允许波动，不重置计数
                        Log.d("LivenessEngine", "张嘴检测: MAR=$mar（0.35-0.38之间，波动中），保持计数=$mouthOpenCount")
                    } else {
                        // 每500ms输出一次MAR值，帮助调试
                        if (mar < 0.35f) {
                            if (mouthOpenCount > 0) {
                                Log.d("LivenessEngine", "张嘴检测中断，MAR=$mar（<0.35）, count=$mouthOpenCount, 重置计数")
                            } else if (now % 500 < 50) {
                                // 每500ms输出一次当前MAR值（当计数为0时）
                                Log.d("LivenessEngine", "张嘴检测: MAR=$mar（<0.38，未张嘴）, count=0, 等待张嘴（MAR>0.38）")
                            }
                        }
                        mouthOpenCount = 0
                    }
                }
            }
            ActionType.SHAKE_HEAD -> {
                // 摇头检测 - 需要完整的左右摆动
                val headPose = estimateHeadPose(landmarks)
                val yaw = headPose.yaw
                val yawAbs = abs(yaw)
                val now = System.currentTimeMillis()
                
                // 如果头部姿态估计异常，跳过检测
                if (yawAbs > 90f) {
                    if (now % 500 < 50) {
                        Log.d("LivenessEngine", "摇头检测: yaw异常（yawAbs>90度），跳过自定义检测。yaw=$yaw")
                    }
                    return
                }
                
                // 状态机检测：需要从一侧摆到另一侧才算完成
                when (headShakeState) {
                    HeadShakeState.CENTER -> {
                        // 中间状态，必须从正面（yaw在-15到15度之间）开始
                        val isFrontFace = yawAbs <= 15f  // 正面范围：-15到15度
                        
                        if (isFrontFace) {
                            // 在正面，记录初始yaw值，等待开始摆动
                            if (headShakeInitialYaw == 0f) {
                                headShakeInitialYaw = yaw
                                headShakeStartedFromCenter = true
                                Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: CENTER, 已记录初始yaw（正面）, initialYaw=$headShakeInitialYaw, 等待开始摆动")
                            }
                            
                            // 从正面开始摆动
                            if (yaw < -10f) {
                                // 摆到左侧（必须从正面开始）
                                if (headShakeStartedFromCenter) {
                                    headShakeState = HeadShakeState.LEFT
                                    headShakeMaxYaw = yawAbs
                                    headShakeMinYaw = yaw
                                    headShakeMaxYawValue = yaw
                                    headShakeCount = 1
                                    Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: CENTER -> LEFT, 从正面开始摆动（初始yaw=$headShakeInitialYaw）")
                                }
                            } else if (yaw > 10f) {
                                // 摆到右侧（必须从正面开始）
                                if (headShakeStartedFromCenter) {
                                    headShakeState = HeadShakeState.RIGHT
                                    headShakeMaxYaw = yawAbs
                                    headShakeMinYaw = yaw
                                    headShakeMaxYawValue = yaw
                                    headShakeCount = 1
                                    Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: CENTER -> RIGHT, 从正面开始摆动（初始yaw=$headShakeInitialYaw）")
                                }
                            } else {
                                // 每500ms输出一次当前yaw值
                                if (now % 500 < 50) {
                                    Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs（正面，-15到15度）, 状态: CENTER, initialYaw=$headShakeInitialYaw, 等待摆动（yaw>10度或yaw<-10度）")
                                }
                            }
                        } else {
                            // 不在正面，重置初始值
                            if (headShakeInitialYaw != 0f || headShakeStartedFromCenter) {
                                Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs（不在正面，>10度）, 状态: CENTER, 重置初始值，等待回到正面")
                                headShakeInitialYaw = 0f
                                headShakeStartedFromCenter = false
                            } else {
                                // 每500ms输出一次当前yaw值
                                if (now % 500 < 50) {
                                    Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs（不在正面，>10度）, 状态: CENTER, 等待回到正面（yaw在-10到10度之间）")
                                }
                            }
                        }
                    }
                    HeadShakeState.LEFT -> {
                        // 在左侧，需要摆到右侧才算完成一个周期（必须从正面开始）
                        // 更新yaw的最小值和最大值
                        headShakeMinYaw = minOf(headShakeMinYaw, yaw)
                        headShakeMaxYawValue = maxOf(headShakeMaxYawValue, yaw)
                        headShakeMaxYaw = maxOf(headShakeMaxYaw, yawAbs)
                        
                        if (yaw < -10f) {
                            // 继续在左侧
                            headShakeCount++
                            Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: LEFT, minYaw=$headShakeMinYaw, maxYaw=$headShakeMaxYawValue, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 继续在左侧")
                        } else if (yaw > 10f) {
                            // 快速摆到右侧，完成一个完整的左右摆动
                            headShakeMaxYaw = maxOf(headShakeMaxYaw, yawAbs)
                            Log.d("LivenessEngine", "摇头动作完成（快速摇头检测），yaw=$yaw, yawAbs=$yawAbs, 状态: LEFT -> RIGHT, maxYaw=$headShakeMaxYaw, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 完整周期完成")
                            val completedAction = expectedAction
                            expectedAction = null
                            completedAction?.let { notifySceneSuccess(scene, it) }
                        } else if (abs(yaw) <= 8f && headShakeMinYaw < -10f) {
                            // 回到中心区域，只要之前已经有一次左侧偏移即可完成
                            Log.d("LivenessEngine", "摇头动作完成（轻微回中），yaw=$yaw, headShakeMinYaw=$headShakeMinYaw, initialYaw=$headShakeInitialYaw, count=$headShakeCount")
                            val completedAction = expectedAction
                            expectedAction = null
                            completedAction?.let { notifySceneSuccess(scene, it) }
                        } else {
                            val yawRange = headShakeMaxYawValue - headShakeMinYaw
                            if (headShakeMinYaw < -10f && headShakeMaxYawValue > 10f && yawRange > 20f) {
                                Log.d("LivenessEngine", "摇头动作完成（缓慢摇头检测），yaw=$yaw, minYaw=$headShakeMinYaw, maxYaw=$headShakeMaxYawValue, range=$yawRange（>20度，跨越左右两侧）, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 完整周期完成")
                                val completedAction = expectedAction
                                expectedAction = null
                                completedAction?.let { notifySceneSuccess(scene, it) }
                            } else if (headShakeMaxYaw < 20f) {
                                // 摆动幅度不够，重置
                                headShakeState = HeadShakeState.CENTER
                                headShakeCount = 0
                                headShakeMaxYaw = 0f
                                headShakeMinYaw = 0f
                                headShakeMaxYawValue = 0f
                                headShakeInitialYaw = 0f
                                headShakeStartedFromCenter = false
                                Log.d("LivenessEngine", "摇头检测中断，yaw=$yaw, yawAbs=$yawAbs, maxYaw=$headShakeMaxYaw（<20度，摆动幅度不够）, 状态: LEFT -> CENTER, 重置")
                            } else {
                                // 摆动幅度足够，允许回到中间（可能是过渡）
                                Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: LEFT（过渡到中间）, maxYaw=$headShakeMaxYaw（>=20度，幅度足够）, initialYaw=$headShakeInitialYaw, count=$headShakeCount")
                            }
                        }
                    }
                    HeadShakeState.RIGHT -> {
                        // 在右侧，需要摆到左侧才算完成一个周期（必须从正面开始）
                        // 更新yaw的最小值和最大值
                        headShakeMinYaw = minOf(headShakeMinYaw, yaw)
                        headShakeMaxYawValue = maxOf(headShakeMaxYawValue, yaw)
                        headShakeMaxYaw = maxOf(headShakeMaxYaw, yawAbs)
                        
                        if (yaw > 10f) {
                            // 继续在右侧
                            headShakeCount++
                            Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: RIGHT, minYaw=$headShakeMinYaw, maxYaw=$headShakeMaxYawValue, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 继续在右侧")
                        } else if (yaw < -10f) {
                            // 快速摆到左侧，完成一个完整的右左摆动
                            headShakeMaxYaw = maxOf(headShakeMaxYaw, yawAbs)
                            Log.d("LivenessEngine", "摇头动作完成（快速摇头检测），yaw=$yaw, yawAbs=$yawAbs, 状态: RIGHT -> LEFT, maxYaw=$headShakeMaxYaw, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 完整周期完成")
                            val completedAction = expectedAction
                            expectedAction = null
                            completedAction?.let { notifySceneSuccess(scene, it) }
                        } else if (abs(yaw) <= 8f && headShakeMaxYawValue > 10f) {
                            Log.d("LivenessEngine", "摇头动作完成（轻微回中），yaw=$yaw, headShakeMaxYawValue=$headShakeMaxYawValue, initialYaw=$headShakeInitialYaw, count=$headShakeCount")
                            val completedAction = expectedAction
                            expectedAction = null
                            completedAction?.let { notifySceneSuccess(scene, it) }
                        } else {
                            val yawRange = headShakeMaxYawValue - headShakeMinYaw
                            if (headShakeMinYaw < -10f && headShakeMaxYawValue > 10f && yawRange > 20f) {
                                Log.d("LivenessEngine", "摇头动作完成（缓慢摇头检测），yaw=$yaw, minYaw=$headShakeMinYaw, maxYaw=$headShakeMaxYawValue, range=$yawRange（>20度，跨越左右两侧）, initialYaw=$headShakeInitialYaw, count=$headShakeCount, 完整周期完成")
                                val completedAction = expectedAction
                                expectedAction = null
                                completedAction?.let { notifySceneSuccess(scene, it) }
                            } else if (headShakeMaxYaw < 20f) {
                                // 摆动幅度不够，重置
                                headShakeState = HeadShakeState.CENTER
                                headShakeCount = 0
                                headShakeMaxYaw = 0f
                                headShakeMinYaw = 0f
                                headShakeMaxYawValue = 0f
                                headShakeInitialYaw = 0f
                                headShakeStartedFromCenter = false
                                Log.d("LivenessEngine", "摇头检测中断，yaw=$yaw, yawAbs=$yawAbs, maxYaw=$headShakeMaxYaw（<20度，摆动幅度不够）, 状态: RIGHT -> CENTER, 重置")
                            } else {
                                // 摆动幅度足够，允许回到中间（可能是过渡）
                                Log.d("LivenessEngine", "摇头检测: yaw=$yaw, yawAbs=$yawAbs, 状态: RIGHT（过渡到中间）, maxYaw=$headShakeMaxYaw（>=20度，幅度足够）, initialYaw=$headShakeInitialYaw, count=$headShakeCount")
                            }
                        }
                    }
                }
            }
            ActionType.NOD_HEAD -> {
                // 点头检测
                val noseTip = landmarks[4]
                val chin = landmarks[152]
                val noseChinDistance = abs(noseTip.y - chin.y)
                
                // 初始化基准距离
                if (nodBaseDistance == 0f) {
                    nodBaseDistance = noseChinDistance
                    return
                }
                
                // 使用头部姿态估计作为辅助判断
                val headPose = estimateHeadPose(landmarks)
                val pitch = headPose.pitch
                val pitchAbs = abs(pitch)
                
                // 检测距离变化或pitch角度
                val isNoddingByPitch = if (pitchAbs <= 90f) {
                    pitch < -10f  // 从-15放宽到-10
                } else {
                    false
                }
                val distanceIncrease = noseChinDistance - nodBaseDistance
                val isNoddingByDistance = distanceIncrease > 0.03f  // 从0.05降低到0.03

                if (isNoddingByPitch || isNoddingByDistance) {
                    headShakeCount++
                    if (headShakeCount >= 8) {  // 从12帧降低到8帧
                        Log.d("LivenessEngine", "点头动作完成（自定义检测），pitch=$pitch, distance=$noseChinDistance, base=$nodBaseDistance, increase=$distanceIncrease, count=$headShakeCount")
                        // 清除当前动作，防止重复检测
                        val completedAction = expectedAction
                        expectedAction = null
                        headShakeCount = 0
                        nodBaseDistance = 0f  // 重置基准距离
                        completedAction?.let { notifySceneSuccess(scene, it) }
                    }
                } else {
                    if (headShakeCount > 0) {
                        Log.d("LivenessEngine", "点头检测中断，pitch=$pitch, distance=$noseChinDistance, base=$nodBaseDistance, increase=$distanceIncrease, count=$headShakeCount")
                    }
                    headShakeCount = 0
                    // 更新基准距离
                    if (noseChinDistance < nodBaseDistance - 0.02f) {
                        nodBaseDistance = noseChinDistance
                    }
                }
            }
            null -> {}
        }

        // 进度更新已在updateProgress()方法中统一处理
    }

    /**
     * 抓拍检测
     * @param scene 当前场景
     * @param landmarks 人脸关键点
     */
    private fun checkCapture(scene: Scene, landmarks: List<LandmarkProto.NormalizedLandmark>) {
        // 根据场景决定是否检查框内和抓拍策略
        // FACE_IN_FRAME_CAPTURE场景：已经在checkFaceInFrame中检查过框内，这里立即抓拍
        // CAPTURE场景：检查框内，需要连续1秒满足条件才抓拍
        val isImmediateCapture = scene == Scene.FACE_IN_FRAME_CAPTURE
        
        if (!isImmediateCapture) {
            // CAPTURE场景：检查是否在框内
            val faceRect = getFaceRect(landmarks)
            val faceSize = getFaceSize(faceRect)
            val faceCenter = getFaceCenter(faceRect)
            
            val frameCenterX = 0.5f
            val frameCenterY = 0.4f  // 与UI绘制位置一致
            val frameRadius = 0.42f  // 与UI绘制一致
            
            val distanceFromCenter = sqrt(
                (faceCenter.x - frameCenterX).pow(2) +
                        (faceCenter.y - frameCenterY).pow(2)
            )
            
            val isInFrame = distanceFromCenter < frameRadius * 0.95f
            val isSizeFit = faceSize in 0.1f..0.8f
            
            // 检查四个角点是否都在框内（防止人脸一半在框外）
            val cornerMarginRatio = 0.9f
            val corners = listOf(
                Point(faceRect.left, faceRect.top),
                Point(faceRect.left, faceRect.bottom),
                Point(faceRect.right, faceRect.top),
                Point(faceRect.right, faceRect.bottom)
            )
            val areCornersInside = corners.all {
                val dist = sqrt((it.x - frameCenterX).pow(2) + (it.y - frameCenterY).pow(2))
                dist <= frameRadius * cornerMarginRatio
            }
            
            val horizontalMargin = 0.02f
            val verticalMargin = 0.03f
            val isWithinBounds =
                faceRect.left >= frameCenterX - frameRadius + horizontalMargin &&
                faceRect.right <= frameCenterX + frameRadius - horizontalMargin &&
                faceRect.top >= frameCenterY - frameRadius + verticalMargin &&
                faceRect.bottom <= frameCenterY + frameRadius - verticalMargin
            
            if (!isInFrame || !isSizeFit || !areCornersInside || !isWithinBounds) {
                callback?.onTip(scene, null, CODE_NOT_IN_FRAME)
                return
            }
        }
        
        // 检查人脸是否正面（使用与checkFaceInFrame相同的宽松逻辑）
        val headPose = estimateHeadPose(landmarks)
        val yawAbs = abs(headPose.yaw)
        val pitchAbs = abs(headPose.pitch)
        
        // 如果角度异常（可能是z坐标问题），暂时认为正面
        val isFrontFace = if (yawAbs > 90f || pitchAbs > 90f) {
            // 角度异常，可能是z坐标问题，暂时认为正面
            true
        } else {
            yawAbs < 30f && pitchAbs < 30f  // 使用30度阈值
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 添加日志
        if (currentTime % 500 < 50) {  // 每500ms输出一次
            Log.d("LivenessEngine", "抓拍检测: scene=$scene, isImmediate=$isImmediateCapture, yaw=${headPose.yaw}, pitch=${headPose.pitch}, isFrontFace=$isFrontFace, readyTime=${if (captureReadyStartTime > 0) currentTime - captureReadyStartTime else 0}ms")
        }

        if (isFrontFace) {
            if (isImmediateCapture) {
                // FACE_IN_FRAME_CAPTURE场景：立即抓拍
                analysisBitmap?.let { bitmap ->
                    Log.d("LivenessEngine", "抓拍成功（立即抓拍），bitmap大小: ${bitmap.width}x${bitmap.height}")
                    val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                    notifyCaptureSuccess(scene, bitmap.copy(config, false))
                } ?: run {
                    Log.w("LivenessEngine", "抓拍失败：analysisBitmap为null")
                    notifySceneFailure(scene, null, CODE_CAPTURE_BITMAP_NULL)
                }
            } else {
                // CAPTURE场景：需要连续1秒满足条件才抓拍
                if (captureReadyStartTime == 0L) {
                    // 第一次满足条件，开始计时
                    captureReadyStartTime = currentTime
                    Log.d("LivenessEngine", "抓拍准备开始计时")
                } else {
                    // 检查是否连续满足条件达到要求时间
                    val duration = currentTime - captureReadyStartTime
                    if (duration >= captureReadyRequiredDuration) {
                        // 连续满足条件达到要求时间，执行抓拍
                        Log.d("LivenessEngine", "抓拍条件满足，连续${duration}ms在框内且正面，开始抓拍")
            analysisBitmap?.let { bitmap ->
                            Log.d("LivenessEngine", "抓拍成功，bitmap大小: ${bitmap.width}x${bitmap.height}")
                 val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                            captureReadyStartTime = 0L  // 重置，防止重复抓拍
                            notifyCaptureSuccess(scene, bitmap.copy(config, false))
            } ?: run {
                            Log.w("LivenessEngine", "抓拍失败：analysisBitmap为null")
                            captureReadyStartTime = 0L  // 重置
                            notifySceneFailure(scene, null, CODE_CAPTURE_BITMAP_NULL)
                        }
                    } else {
                        // 还在计时中，提示保持
                        callback?.onTip(scene, null, CODE_NOT_IN_FRAME)
                    }
                }
            }
        } else {
            // 不满足条件，重置计时
            if (captureReadyStartTime != 0L) {
                Log.d("LivenessEngine", "抓拍准备中断，重置计时")
                captureReadyStartTime = 0L
            }
            callback?.onTip(scene, null, CODE_FACE_NOT_FRONT)
        }
    }

    /**
     * 计算 EAR (Eye Aspect Ratio) - 用于眨眼检测
     */
    private fun calculateEAR(landmarks: List<LandmarkProto.NormalizedLandmark>): Float {
        // 左眼关键点索引（MediaPipe Face Mesh 的索引）
        // val leftEyeIndices = listOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
        // val rightEyeIndices = listOf(362, 382, 381, 380, 374, 373, 390, 249, 263, 466, 388, 387, 386, 385, 384, 398)

        // 简化的 EAR 计算（使用关键点）
        val leftEyeTop = landmarks[159].y
        val leftEyeBottom = landmarks[145].y
        val leftEyeWidth = abs(landmarks[33].x - landmarks[133].x)

        val rightEyeTop = landmarks[386].y
        val rightEyeBottom = landmarks[374].y
        val rightEyeWidth = abs(landmarks[362].x - landmarks[263].x)

        val leftEAR = abs(leftEyeTop - leftEyeBottom) / leftEyeWidth
        val rightEAR = abs(rightEyeTop - rightEyeBottom) / rightEyeWidth

        return (leftEAR + rightEAR) / 2f
    }

    /**
     * 计算 MAR (Mouth Aspect Ratio) - 用于张嘴检测
     */
    private fun calculateMAR(landmarks: List<LandmarkProto.NormalizedLandmark>): Float {
        // 嘴巴关键点索引
        val mouthTop = landmarks[13].y
        val mouthBottom = landmarks[14].y
        val mouthWidth = abs(landmarks[61].x - landmarks[291].x)

        return abs(mouthTop - mouthBottom) / mouthWidth
    }

    /**
     * 估计头部姿态（用于摇头/点头检测）
     * 使用CPSP优化的算法，避免z坐标不稳定导致的异常值
     */
    private fun estimateHeadPose(landmarks: List<LandmarkProto.NormalizedLandmark>): HeadPose {
        // MediaPipe 关键点索引说明：
        // 4: 鼻尖 (Nose tip)
        // 234: 右侧脸边缘 (Image Left / User Right)
        // 454: 左侧脸边缘 (Image Right / User Left)
        // 10: 额头顶部
        // 152: 下巴底部

        val noseTip = landmarks[4]
        val leftBoundary = landmarks[234]
        val rightBoundary = landmarks[454]

        // --- Yaw 左右摇头计算 ---
        // 计算鼻尖在脸部水平投影中的位置比例
        // 正脸时，noseTip.x 应该在 leftBoundary.x 和 rightBoundary.x 的中间
        val faceWidth = rightBoundary.x - leftBoundary.x

        // 防止除以 0
        val yaw = if (faceWidth > 0.001f) {
            val nosePos = (noseTip.x - leftBoundary.x) / faceWidth
            // nosePos 为 0.5 表示正中
            // 映射：0.5 -> 0度； 0.2 -> 向一侧转； 0.8 -> 向另一侧转
            (nosePos - 0.5f) * 120f // 120是感官系数，可调
        } else {
            0f
        }

        // --- Pitch 上下点头计算 ---
        val top = landmarks[10]
        val bottom = landmarks[152]
        val faceHeight = bottom.y - top.y
        val pitch = if (faceHeight > 0.001f) {
            val noseVerticalPos = (noseTip.y - top.y) / faceHeight
            // 正脸时鼻尖高度约在脸部垂直方向的 0.55-0.6 处
            (noseVerticalPos - 0.55f) * 100f
        } else {
            0f
        }

        return HeadPose(yaw = yaw, pitch = pitch, roll = 0f)
    }

    data class HeadPose(val yaw: Float, val pitch: Float, val roll: Float)

    private fun getFaceRect(landmarks: List<LandmarkProto.NormalizedLandmark>): FaceRect {
        val xs = landmarks.map { it.x }
        val ys = landmarks.map { it.y }
        return FaceRect(
            left = xs.minOrNull() ?: 0f,
            top = ys.minOrNull() ?: 0f,
            right = xs.maxOrNull() ?: 1f,
            bottom = ys.maxOrNull() ?: 1f
        )
    }

    private fun getFaceSize(rect: FaceRect): Float {
        // 使用最大边长作为人脸尺寸，与CPSP保持一致
        return max(rect.right - rect.left, rect.bottom - rect.top)
    }

    private fun getFaceCenter(rect: FaceRect): Point {
        return Point(
            x = (rect.left + rect.right) / 2f,
            y = (rect.top + rect.bottom) / 2f
        )
    }

    data class FaceRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class Point(val x: Float, val y: Float)

    /**
     * 设置每个场景的超时时间（毫秒）
     * @param timeout 超时时间，单位：毫秒。默认值为20000（20秒）
     */
    fun setSceneTimeout(timeout: Long) {
        require(timeout > 0) { "超时时间必须大于0" }
        sceneTimeout = timeout
    }

    /**
     * 切换到指定场景
     * @param scene 场景类型
     * @param action 动作类型（当scene为ACTION时，此参数不能为空）
     */
    fun switchToScene(scene: Scene, action: ActionType? = null) {
        // 验证：ACTION场景时，action不能为空
        if (scene == Scene.ACTION && action == null) {
            // 直接调用失败回调，不抛出异常
            notifySceneFailure(scene, null, CODE_ACTION_TYPE_NULL)
            return
        }
        
        currentScene = scene
        sceneStartTime = System.currentTimeMillis()
        
        // 根据场景重置相关状态
        when (scene) {
            Scene.FACE_IN_FRAME, Scene.FACE_IN_FRAME_CAPTURE -> {
                faceInFrameStartTime = 0L
        faceInFrameCount = 0
            }
            Scene.ACTION -> {
                // ACTION场景：设置动作类型并重置计数器
                expectedAction = action
                blinkCount = 0
                blinkState = BlinkState.OPEN
                mouthOpenCount = 0
                headShakeCount = 0
                headShakeState = HeadShakeState.CENTER
                headShakeMaxYaw = 0f
                headShakeMinYaw = 0f
                headShakeMaxYawValue = 0f
                headShakeInitialYaw = 0f
                headShakeStartedFromCenter = false
                nodBaseDistance = 0f  // 重置点头基准距离
                Log.d("LivenessEngine", "切换到场景: $scene, 动作类型: $action")
            }
            Scene.CAPTURE -> {
                captureReadyStartTime = 0L
            }
        }
        
        if (scene != Scene.ACTION) {
            Log.d("LivenessEngine", "切换到场景: $scene")
        }
    }

    /**
     * 处理 CameraX 的 ImageProxy 帧
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class) 
    fun processFrame(imageProxy: ImageProxy) {
        val image = imageProxy.image ?: run {
            Log.d("LivenessEngine", "processFrame: image为null")
            imageProxy.close()
            return
        }

        // 1. 初始化或调整缓存 Bitmap 的大小
        if (analysisBitmap == null || analysisBitmap?.width != imageProxy.width || analysisBitmap?.height != imageProxy.height) {
            analysisBitmap = createBitmap(imageProxy.width, imageProxy.height)
            Log.d("LivenessEngine", "创建新的analysisBitmap: ${imageProxy.width}x${imageProxy.height}")
        }

        // 2. 将 YUV 转换为 Bitmap
        analysisBitmap?.let { bitmap ->
            yuvToRgbConverter.yuvToRgb(image, bitmap)
            
            // 3. 处理旋转
            val rotatedBitmap = if (imageProxy.imageInfo.rotationDegrees != 0) {
                rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            } else {
                bitmap
            }
            
            // 4. 发送给 MediaPipe (带时间戳)
            if (faceMesh != null) {
            faceMesh?.send(rotatedBitmap, System.currentTimeMillis())
            } else {
                Log.w("LivenessEngine", "processFrame: faceMesh为null，无法处理")
            }
            
            // 如果进行了旋转，rotatedBitmap 是新创建的，需要适时回收（但在持续分析中通常依赖 GC）
            // analysisBitmap 是复用的，不需要手动 recycle
        } ?: run {
            Log.w("LivenessEngine", "processFrame: analysisBitmap为null")
        }
        
        imageProxy.close()
    }
    
    // 提供一个接收 Bitmap 的方法
    fun processBitmap(bitmap: Bitmap, timestampMs: Long) {
         faceMesh?.send(bitmap, timestampMs)
    }
    
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun checkTimeout() {
        // 只有在场景已设置且计时器已初始化时才检查超时
        if (currentScene == null || sceneStartTime == 0L) {
            return
        }
        val elapsed = System.currentTimeMillis() - sceneStartTime
        if (elapsed > sceneTimeout) {
            val scene = currentScene!!
            val action = expectedAction
            notifySceneFailure(scene, action, CODE_TIMEOUT)
        }
    }
    
    /**
     * 清空场景状态（场景结束后调用）
     */
    private fun clearSceneState() {
        currentScene = null
        expectedAction = null
        sceneStartTime = 0L
        // 重置检测状态
        faceInFrameStartTime = 0L
        faceInFrameCount = 0
        captureReadyStartTime = 0L
        blinkCount = 0
        blinkState = BlinkState.OPEN
        mouthOpenCount = 0
        headShakeCount = 0
        headShakeState = HeadShakeState.CENTER
        headShakeMaxYaw = 0f
        headShakeMinYaw = 0f
        headShakeMaxYawValue = 0f
        headShakeInitialYaw = 0f
        headShakeStartedFromCenter = false
        nodBaseDistance = 0f
    }
    
    /**
     * 通知场景成功（先清空场景状态，再回调）
     */
    private fun notifySceneSuccess(scene: Scene, action: ActionType?) {
        // 先清空场景状态
        clearSceneState()
        // 然后通知成功
        callback?.onSuccess(scene, action)
    }
    
    /**
     * 通知场景失败（先清空场景状态，再回调）
     */
    private fun notifySceneFailure(scene: Scene, action: ActionType?, code: Int) {
        // 先清空场景状态
        clearSceneState()
        // 然后通知失败
        callback?.onFailure(scene, action, code)
    }
    
    /**
     * 通知抓拍成功（先清空场景状态，再回调）
     */
    private fun notifyCaptureSuccess(scene: Scene, bitmap: Bitmap) {
        // 先清空场景状态
        clearSceneState()
        // 然后通知抓拍成功
        callback?.onCaptureSuccess(scene, bitmap)
    }

    fun destroy() {
        faceMesh?.close()
        faceMesh = null
        analysisBitmap = null
    }
}
