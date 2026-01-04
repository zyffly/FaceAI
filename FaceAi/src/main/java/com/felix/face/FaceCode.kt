package com.felix.face

/**
 * 活体检测错误码常量类
 */
object FaceCode {
    // ========== 成功 ==========
    const val CODE_SUCCESS = 0
    
    // ========== 超时 ==========
    const val CODE_TIMEOUT = 502                    // 场景超时
    
    // ========== 不在框内（统一使用） ==========
    const val CODE_NOT_IN_FRAME = 1000              // 检测对象不在框内
    
    // ========== 人脸检测相关 (2000-2999) ==========
    const val CODE_NO_FACE_DETECTED = 2000          // 未检测到人脸
    const val CODE_FACE_SIZE_INVALID = 2001         // 人脸大小不合适（太大或太小）
    const val CODE_FACE_NOT_FRONT = 2002            // 人脸不是正面
    const val CODE_FACE_DETECTED = 2003             // 检测到人脸
    // ========== ACTION相关 (3000-3999) ==========
    const val CODE_ACTION_TYPE_NULL = 2000          // ACTION场景下动作类型为空
    
    // ========== 抓拍相关 (4000-4999) ==========
    const val CODE_CAPTURE_BITMAP_NULL = 4000       // 抓拍时Bitmap为null
    
    // ========== 系统错误 (9000-9999) ==========
    const val CODE_JNI_LOAD_FAILED = 9000           // JNI库加载失败
    const val CODE_ENGINE_ERROR = 9001              // 引擎内部错误
}
