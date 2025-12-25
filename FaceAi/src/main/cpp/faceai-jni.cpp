#include <jni.h>
#include <android/bitmap.h>
#include <libyuv.h>

extern "C"
JNIEXPORT void JNICALL
Java_com_felix_face_YuvToRgbConverter_nativeConvertAndroid420ToBitmap(JNIEnv *env, jobject thiz,
                                                                      jobject y_buffer, jint y_stride,
                                                                      jobject u_buffer, jint u_stride, jint u_pixel_stride,
                                                                      jobject v_buffer, jint v_stride, jint v_pixel_stride,
                                                                      jint width, jint height,
                                                                      jobject output_bitmap) {
    // 1. 获取 Bitmap 信息并锁定像素
    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, output_bitmap, &info) < 0) return;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &pixels) < 0) return;

    // 2. 获取 YUV 缓冲区的指针
    uint8_t *src_y = (uint8_t *) env->GetDirectBufferAddress(y_buffer);
    uint8_t *src_u = (uint8_t *) env->GetDirectBufferAddress(u_buffer);
    uint8_t *src_v = (uint8_t *) env->GetDirectBufferAddress(v_buffer);

    // 3. 调用 libyuv 进行转换
    // Android 的 YUV_420_888 可能是 I420 (semi-planar) 或者 NV21/NV12
    // 如果 u_pixel_stride == 1 且 v_pixel_stride == 1，通常是 I420
    // 如果 pixel_stride == 2，通常是 NV12 或 NV21
    
    // libyuv::Android420ToABGR 是处理 Android CameraX 复杂 stride 的最佳函数
    // 注意：Bitmap.Config.ARGB_8888 在内存中通常是 ABGR 顺序 (Little Endian)
    libyuv::Android420ToABGR(
            src_y, y_stride,
            src_u, u_stride,
            src_v, v_stride,
            u_pixel_stride, // libyuv 需要此参数来处理 semi-planar
            (uint8_t *) pixels, info.stride,
            width, height
    );

    // 4. 解锁像素
    AndroidBitmap_unlockPixels(env, output_bitmap);
}
