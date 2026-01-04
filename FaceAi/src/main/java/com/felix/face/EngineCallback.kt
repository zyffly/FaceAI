package com.felix.face

import android.graphics.Bitmap
import com.google.mediapipe.formats.proto.LandmarkProto

/**
 * 引擎回调接口
 */
interface EngineCallback {
    /**
     * 初始化回调
     * @param code 初始化结果码（成功：FaceCode.CODE_SUCCESS；失败：对应错误码）
     */
    fun onInit(code: Int)
    
    /**
     * 进度回调
     * @param scene 当前场景
     * @param progress 进度值 0.0-1.0
     */
    fun onProgress(scene: Int, progress: Float)
    
    /**
     * 人脸关键点数据回调（每帧都会调用，用于自定义检测逻辑）
     * @param scene 当前场景
     * @param action 动作类型（仅在ACTION场景时不为null）
     * @param landmarks 人脸关键点列表（MediaPipe Face Mesh的468个关键点）
     * @return true 表示用户已处理，SDK跳过默认检测；false 表示用户未处理，SDK执行默认检测
     */
    fun onLandmarks(
        scene: Int, 
        action: Int?, 
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
    fun onTip(scene: Int, action: Int?, code: Int)
    
    /**
     * 场景完成回调（成功/失败统一回调）
     * @param scene 场景类型
     * @param action 动作类型（仅在ACTION场景时不为null）
     * @param code 结果码（成功请传 FaceCode.CODE_SUCCESS）
     */
    fun onCompleted(scene: Int, action: Int?, code: Int)
    
    /**
     * 抓拍回调
     * @param scene 当前场景
     * @param bitmap 抓拍得到的图片
     */
    fun onCapture(scene: Int, bitmap: Bitmap)
}
