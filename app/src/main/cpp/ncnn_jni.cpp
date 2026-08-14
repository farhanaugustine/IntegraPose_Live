#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>
#include <string>

#include "allocator.h"
#include "cpu.h"
#include "mat.h"
#include "ncnn_gpu_runtime.h"
#include "net.h"

namespace {

struct NcnnContext {
    ncnn::UnlockedPoolAllocator blob_pool_allocator;
    ncnn::PoolAllocator workspace_pool_allocator;
    ncnn::Net net;
    std::mutex inference_mutex;
    int threads = 1;
    bool using_vulkan = false;
};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

std::string read_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

NcnnContext* from_handle(jlong handle) {
    return reinterpret_cast<NcnnContext*>(static_cast<intptr_t>(handle));
}

jlong to_handle(NcnnContext* context) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_integrapose_mobile_inference_NcnnNative_nativeCreate(
    JNIEnv* env,
    jclass,
    jstring param_path_value,
    jstring weights_path_value,
    jint requested_threads,
    jboolean request_vulkan
) {
    const std::string param_path = read_utf8(env, param_path_value);
    const std::string weights_path = read_utf8(env, weights_path_value);
    if (param_path.empty() || weights_path.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "NCNN model paths cannot be empty.");
        return 0;
    }

    NcnnContext* context = new (std::nothrow) NcnnContext();
    if (context == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not allocate the NCNN model context.");
        return 0;
    }

    context->threads = std::max(1, std::min(8, static_cast<int>(requested_threads)));
    context->blob_pool_allocator.set_size_compare_ratio(0.0f);
    context->workspace_pool_allocator.set_size_compare_ratio(0.0f);
    ncnn::set_omp_dynamic(0);
    ncnn::set_omp_num_threads(context->threads);
    context->net.opt.num_threads = context->threads;
    context->net.opt.blob_allocator = &context->blob_pool_allocator;
    context->net.opt.workspace_allocator = &context->workspace_pool_allocator;
    context->net.opt.lightmode = true;
    context->net.opt.use_packing_layout = true;
    context->net.opt.use_fp16_packed = true;
    context->net.opt.use_fp16_storage = true;
    context->net.opt.use_fp16_arithmetic = true;

    if (request_vulkan == JNI_TRUE) {
#if NCNN_VULKAN
        if (integrapose::ncnn_runtime::gpu_count() <= 0) {
            delete context;
            throw_java(
                env,
                "java/lang/IllegalStateException",
                "NCNN Vulkan was requested, but no compatible Vulkan compute device is available."
            );
            return 0;
        }
        context->net.set_vulkan_device(0);
        context->net.opt.use_vulkan_compute = true;
        context->using_vulkan = true;
#else
        delete context;
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "This NCNN library was built without Vulkan support."
        );
        return 0;
#endif
    }

    const int param_status = context->net.load_param(param_path.c_str());
    if (param_status != 0) {
        delete context;
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "NCNN could not load model.ncnn.param (error " + std::to_string(param_status) + ")."
        );
        return 0;
    }

    const int model_status = context->net.load_model(weights_path.c_str());
    if (model_status != 0) {
        delete context;
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "NCNN could not load model.ncnn.bin (error " + std::to_string(model_status) + ")."
        );
        return 0;
    }

    return to_handle(context);
}

namespace {

jobject run_ncnn_input(
    JNIEnv* env,
    NcnnContext* context,
    const float* input_pointer,
    int width,
    int height
) {
    ncnn::Mat input(
        width,
        height,
        3,
        const_cast<float*>(input_pointer),
        4u,
        1
    );
    ncnn::Extractor extractor = context->net.create_extractor();
    const int input_status = extractor.input("in0", input);
    if (input_status != 0) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "NCNN rejected input blob in0 (error " +
                std::to_string(input_status) + ")."
        );
        return nullptr;
    }

    ncnn::Mat output;
    const int output_status = extractor.extract("out0", output);
    if (output_status != 0 || output.empty()) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "NCNN could not extract output blob out0 (error " +
                std::to_string(output_status) + ")."
        );
        return nullptr;
    }

    ncnn::Mat float_output = output;
    if (float_output.elembits() == 16) {
        ncnn::Mat converted;
        ncnn::cast_float16_to_float32(
            float_output,
            converted,
            context->net.opt
        );
        float_output = converted;
    }
    if (float_output.elempack != 1) {
        ncnn::Mat unpacked;
        ncnn::convert_packing(
            float_output,
            unpacked,
            1,
            context->net.opt
        );
        float_output = unpacked;
    }
    if (float_output.elembits() != 32 || float_output.dims != 2) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "NCNN produced an unsupported output tensor layout."
        );
        return nullptr;
    }

    const jlong value_count_long =
        static_cast<jlong>(float_output.w) *
        static_cast<jlong>(float_output.h);
    if (value_count_long <= 0 || value_count_long > 100000000LL) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "NCNN output tensor size is invalid."
        );
        return nullptr;
    }
    const jsize value_count = static_cast<jsize>(value_count_long);

    jfloatArray data_array = env->NewFloatArray(value_count);
    if (data_array == nullptr) return nullptr;
    env->SetFloatArrayRegion(
        data_array,
        0,
        value_count,
        reinterpret_cast<const jfloat*>(float_output.data)
    );
    if (env->ExceptionCheck()) return nullptr;

    jlong shape_values[3] = {
        1,
        static_cast<jlong>(float_output.h),
        static_cast<jlong>(float_output.w)
    };
    jlongArray shape_array = env->NewLongArray(3);
    if (shape_array == nullptr) return nullptr;
    env->SetLongArrayRegion(shape_array, 0, 3, shape_values);
    if (env->ExceptionCheck()) return nullptr;

    jclass result_class = env->FindClass(
        "com/integrapose/mobile/inference/NcnnTensorOutput"
    );
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class,
        "<init>",
        "([F[J)V"
    );
    if (constructor == nullptr) return nullptr;
    return env->NewObject(
        result_class,
        constructor,
        data_array,
        shape_array
    );
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_integrapose_mobile_inference_NcnnNative_nativeRun(
    JNIEnv* env,
    jclass,
    jlong handle,
    jfloatArray input_values,
    jint width,
    jint height
) {
    NcnnContext* context = from_handle(handle);
    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "The NCNN model is not loaded.");
        return nullptr;
    }
    if (input_values == nullptr || width <= 0 || height <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "The NCNN input tensor is invalid.");
        return nullptr;
    }

    const jlong expected_values =
        static_cast<jlong>(width) * static_cast<jlong>(height) * 3LL;
    if (env->GetArrayLength(input_values) != expected_values) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "The NCNN input tensor does not match the requested RGB dimensions."
        );
        return nullptr;
    }

    jfloat* input_pointer = env->GetFloatArrayElements(input_values, nullptr);
    if (input_pointer == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "Could not access the NCNN input tensor.");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(context->inference_mutex);
    jobject result = run_ncnn_input(
        env,
        context,
        input_pointer,
        static_cast<int>(width),
        static_cast<int>(height)
    );
    env->ReleaseFloatArrayElements(input_values, input_pointer, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_integrapose_mobile_inference_NcnnNative_nativeRunDirect(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject input_buffer,
    jint width,
    jint height
) {
    NcnnContext* context = from_handle(handle);
    if (context == nullptr) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The NCNN model is not loaded."
        );
        return nullptr;
    }
    if (input_buffer == nullptr || width <= 0 || height <= 0) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "The NCNN direct input tensor is invalid."
        );
        return nullptr;
    }

    const jlong expected_bytes =
        static_cast<jlong>(width) *
        static_cast<jlong>(height) *
        3LL *
        static_cast<jlong>(sizeof(float));
    void* input_address = env->GetDirectBufferAddress(input_buffer);
    const jlong capacity = env->GetDirectBufferCapacity(input_buffer);
    if (input_address == nullptr || capacity < expected_bytes) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "The NCNN direct input buffer does not match the requested RGB dimensions."
        );
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(context->inference_mutex);
    return run_ncnn_input(
        env,
        context,
        static_cast<const float*>(input_address),
        static_cast<int>(width),
        static_cast<int>(height)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_integrapose_mobile_inference_NcnnNative_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle
) {
    NcnnContext* context = from_handle(handle);
    delete context;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_integrapose_mobile_inference_NcnnNative_nativeGpuCount(
    JNIEnv*,
    jclass
) {
    return static_cast<jint>(integrapose::ncnn_runtime::gpu_count());
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    integrapose::ncnn_runtime::destroy_gpu_instance();
}
