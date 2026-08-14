#include "ncnn_gpu_runtime.h"

#include <atomic>
#include <mutex>

#include "gpu.h"

namespace integrapose::ncnn_runtime {
namespace {

std::once_flag gpu_once;
std::atomic<bool> gpu_initialized(false);

}  // namespace

bool ensure_gpu_instance() {
#if NCNN_VULKAN
    std::call_once(gpu_once, []() {
        if (ncnn::create_gpu_instance() == 0) {
            gpu_initialized.store(true);
        }
    });
    return gpu_initialized.load();
#else
    return false;
#endif
}

int gpu_count() {
#if NCNN_VULKAN
    return ensure_gpu_instance() ? ncnn::get_gpu_count() : 0;
#else
    return 0;
#endif
}

void destroy_gpu_instance() {
#if NCNN_VULKAN
    if (gpu_initialized.exchange(false)) {
        ncnn::destroy_gpu_instance();
    }
#endif
}

}  // namespace integrapose::ncnn_runtime
