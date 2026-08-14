#pragma once

namespace integrapose::ncnn_runtime {

bool ensure_gpu_instance();
int gpu_count();
void destroy_gpu_instance();

}  // namespace integrapose::ncnn_runtime
