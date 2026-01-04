package com.felix.face

/**
 * 场景类型常量
 * 集成者可以定义自定义场景类型（建议从 100 开始，避免与内置场景冲突）
 */
object Scene {
    /**
     * 人脸入框检测场景（检测到入框后回调，不抓拍）
     */
    const val FACE_IN_FRAME = 0
    
    /**
     * 人脸入框检测场景，检测到入框后立即抓拍
     */
    const val FACE_IN_FRAME_CAPTURE = 1
    
    /**
     * 动作检测场景
     */
    const val ACTION = 2
    
    /**
     * 直接抓拍场景（需要人脸在框内且正面，连续1秒满足条件后抓拍）
     */
    const val CAPTURE = 3
}
