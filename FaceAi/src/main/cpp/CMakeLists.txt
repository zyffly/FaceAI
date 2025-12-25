cmake_minimum_required(VERSION 3.18.1)

project("faceai-jni")

# 包含 libyuv 头文件路径
include_directories(libyuv/include)

# 将 libyuv 作为子项目添加
# 注意：libyuv 源码根目录下应该有一个 CMakeLists.txt
add_subdirectory(libyuv)

# 定义我们的 JNI 库
add_library(faceai-jni SHARED
        faceai-jni.cpp)

# 查找 NDK 提供的库
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics) # Bitmap 操作需要

# 链接库
# 'yuv' 是 libyuv CMakeLists.txt 中定义的库名称
target_link_libraries(faceai-jni
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        yuv)
