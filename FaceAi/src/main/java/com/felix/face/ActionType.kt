package com.felix.face

/**
 * 动作类型常量
 * 集成者可以定义自定义动作类型（建议从 100 开始，避免与内置动作冲突）
 */
object ActionType {
    /**
     * 眨眼
     */
    const val BLINK = 0
    
    /**
     * 张嘴
     */
    const val MOUTH_OPEN = 1
    
    /**
     * 摇头
     */
    const val SHAKE_HEAD = 2
    
    /**
     * 点头
     */
    const val NOD_HEAD = 3
}
