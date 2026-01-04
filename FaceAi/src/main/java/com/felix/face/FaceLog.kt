package com.felix.face

import android.util.Log

/**
 * FaceAI 统一日志工具类
 * 提供统一的日志开关控制
 */
object FaceLog {
    private const val TAG = "LivenessEngine"
    
    /**
     * 日志开关，默认为 false（关闭日志）
     * 集成者可以通过 setLogEnabled(true) 开启日志
     */
    @Volatile
    private var isLogEnabled = false
    
    /**
     * 设置日志开关
     * @param enabled true 开启日志，false 关闭日志
     */
    fun setLogEnabled(enabled: Boolean) {
        isLogEnabled = enabled
    }
    
    /**
     * 获取当前日志开关状态
     */
    fun isLogEnabled(): Boolean = isLogEnabled
    
    /**
     * Debug 级别日志
     */
    fun d(message: String) {
        if (isLogEnabled) {
            Log.d(TAG, message)
        }
    }
    
    /**
     * Warning 级别日志
     */
    fun w(message: String) {
        if (isLogEnabled) {
            Log.w(TAG, message)
        }
    }
    
    /**
     * Error 级别日志
     */
    fun e(message: String, throwable: Throwable? = null) {
        if (isLogEnabled) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }
    
    /**
     * Info 级别日志
     */
    fun i(message: String) {
        if (isLogEnabled) {
            Log.i(TAG, message)
        }
    }
}
