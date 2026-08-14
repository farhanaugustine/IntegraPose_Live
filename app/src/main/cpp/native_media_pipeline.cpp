#include <jni.h>

#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "allocator.h"
#include "cpu.h"
#include "mat.h"
#include "net.h"
#include "native_annotated_encoder.h"
#include "ncnn_gpu_runtime.h"

namespace {

constexpr int64_t kCodecTimeoutUs = 10'000;
constexpr int kMaxIdlePolls = 2'000;

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

struct ExtractorDeleter {
    void operator()(AMediaExtractor* value) const {
        if (value != nullptr) AMediaExtractor_delete(value);
    }
};

struct FormatDeleter {
    void operator()(AMediaFormat* value) const {
        if (value != nullptr) AMediaFormat_delete(value);
    }
};

struct ReaderDeleter {
    void operator()(AImageReader* value) const {
        if (value != nullptr) AImageReader_delete(value);
    }
};

struct ImageDeleter {
    void operator()(AImage* value) const {
        if (value != nullptr) AImage_delete(value);
    }
};

using ExtractorPtr = std::unique_ptr<AMediaExtractor, ExtractorDeleter>;
using FormatPtr = std::unique_ptr<AMediaFormat, FormatDeleter>;
using ReaderPtr = std::unique_ptr<AImageReader, ReaderDeleter>;
using ImagePtr = std::unique_ptr<AImage, ImageDeleter>;

struct CodecGuard {
    AMediaCodec* value = nullptr;
    bool started = false;

    ~CodecGuard() {
        if (value == nullptr) return;
        if (started) AMediaCodec_stop(value);
        AMediaCodec_delete(value);
    }
};

struct VideoSource {
    int duplicated_fd = -1;
    ExtractorPtr extractor;
    FormatPtr format;
    std::string mime;
    int32_t width = 0;
    int32_t height = 0;
    int32_t rotation_degrees = 0;
    int64_t duration_us = 0;
    double frame_rate = 0.0;

    ~VideoSource() {
        format.reset();
        extractor.reset();
        if (duplicated_fd >= 0) close(duplicated_fd);
    }
};

bool open_video_source(
    int fd,
    int64_t offset,
    int64_t length,
    VideoSource& source,
    std::string& error
) {
    if (fd < 0 || offset < 0 || length <= 0) {
        error = "The native video file descriptor is invalid.";
        return false;
    }
    source.duplicated_fd = dup(fd);
    if (source.duplicated_fd < 0) {
        error = "The native pipeline could not duplicate the video descriptor.";
        return false;
    }
    source.extractor.reset(AMediaExtractor_new());
    if (!source.extractor) {
        error = "The native pipeline could not create a media extractor.";
        return false;
    }
    const media_status_t source_status = AMediaExtractor_setDataSourceFd(
        source.extractor.get(),
        source.duplicated_fd,
        static_cast<off64_t>(offset),
        static_cast<off64_t>(length)
    );
    if (source_status != AMEDIA_OK) {
        error = "The native media extractor rejected the selected video (error " +
            std::to_string(source_status) + ").";
        return false;
    }

    const size_t track_count = AMediaExtractor_getTrackCount(source.extractor.get());
    for (size_t index = 0; index < track_count; ++index) {
        FormatPtr candidate(AMediaExtractor_getTrackFormat(source.extractor.get(), index));
        if (!candidate) continue;
        const char* mime_value = nullptr;
        if (!AMediaFormat_getString(
                candidate.get(),
                AMEDIAFORMAT_KEY_MIME,
                &mime_value
            ) ||
            mime_value == nullptr ||
            std::string(mime_value).rfind("video/", 0) != 0) {
            continue;
        }
        if (AMediaExtractor_selectTrack(source.extractor.get(), index) != AMEDIA_OK) {
            error = "The native media extractor could not select the video track.";
            return false;
        }
        source.mime = mime_value;
        source.format = std::move(candidate);
        break;
    }
    if (!source.format) {
        error = "The selected file does not contain a readable video track.";
        return false;
    }

    AMediaFormat_getInt32(source.format.get(), AMEDIAFORMAT_KEY_WIDTH, &source.width);
    AMediaFormat_getInt32(source.format.get(), AMEDIAFORMAT_KEY_HEIGHT, &source.height);
    AMediaFormat_getInt64(
        source.format.get(),
        AMEDIAFORMAT_KEY_DURATION,
        &source.duration_us
    );
    AMediaFormat_getInt32(
        source.format.get(),
        "rotation-degrees",
        &source.rotation_degrees
    );
    int32_t integer_rate = 0;
    float float_rate = 0.0f;
    if (AMediaFormat_getInt32(
            source.format.get(),
            AMEDIAFORMAT_KEY_FRAME_RATE,
            &integer_rate
        )) {
        source.frame_rate = static_cast<double>(integer_rate);
    } else if (AMediaFormat_getFloat(
                   source.format.get(),
                   AMEDIAFORMAT_KEY_FRAME_RATE,
                   &float_rate
               )) {
        source.frame_rate = static_cast<double>(float_rate);
    }
    if (source.width <= 0 || source.height <= 0) {
        error = "The native video track has invalid dimensions.";
        return false;
    }
    return true;
}

jobject make_video_info(JNIEnv* env, const VideoSource& source) {
    jclass result_class = env->FindClass(
        "com/integrapose/mobile/offline/NativeVideoInfo"
    );
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class,
        "<init>",
        "(IIIJDLjava/lang/String;)V"
    );
    if (constructor == nullptr) return nullptr;
    jstring mime = env->NewStringUTF(source.mime.c_str());
    if (mime == nullptr) return nullptr;
    return env->NewObject(
        result_class,
        constructor,
        static_cast<jint>(source.width),
        static_cast<jint>(source.height),
        static_cast<jint>(source.rotation_degrees),
        static_cast<jlong>(source.duration_us),
        static_cast<jdouble>(source.frame_rate),
        mime
    );
}

struct ImageSignal {
    std::mutex mutex;
    std::condition_variable available;
    uint64_t generation = 0;
};

void on_image_available(void* context, AImageReader* reader);

bool acquire_decoded_image(
    AImageReader* reader,
    ImageSignal& signal,
    ImagePtr& image,
    std::string& error
);

bool validate_yuv_image(
    const AImage* image,
    int32_t expected_width,
    int32_t expected_height,
    std::string& error
);

jobject make_decode_benchmark(
    JNIEnv* env,
    const VideoSource& source,
    int frames_decoded,
    int frames_requested,
    int64_t wall_time_ms,
    double decode_fps,
    const std::string& decoder_name,
    bool eos_reached
);

bool make_ncnn_input(
    const AImage* image,
    int source_width,
    int source_height,
    int input_size,
    const ncnn::Option& option,
    std::vector<uint8_t>& rgb,
    std::vector<uint8_t>* i420,
    ncnn::Mat& input,
    std::string& error
);

using NativeKeypoint = integrapose::media::OverlayKeypoint;
using NativeDetection = integrapose::media::OverlayDetection;

struct PreparedNativeFrame {
    ncnn::Mat input;
    std::vector<uint8_t> i420;
    int sequence_index = 0;
    int source_frame_index = 0;
    int64_t presentation_time_us = 0;
    int64_t preprocessing_time_ns = 0;
};

struct InferredNativeFrame {
    PreparedNativeFrame frame;
    ncnn::Mat output;
    int64_t inference_time_ns = 0;
};

class PreparedFrameQueue {
public:
    explicit PreparedFrameQueue(size_t capacity) : capacity_(capacity) {}

    bool push(
        PreparedNativeFrame&& frame,
        std::chrono::nanoseconds& wait_time
    ) {
        const auto wait_start = std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> lock(mutex_);
        not_full_.wait(lock, [this]() {
            return cancelled_ || frames_.size() < capacity_;
        });
        wait_time += std::chrono::steady_clock::now() - wait_start;
        if (cancelled_) return false;
        frames_.push_back(std::move(frame));
        not_empty_.notify_one();
        return true;
    }

    bool pop(PreparedNativeFrame& frame) {
        std::unique_lock<std::mutex> lock(mutex_);
        not_empty_.wait(lock, [this]() {
            return cancelled_ || finished_ || !frames_.empty();
        });
        if (cancelled_ || frames_.empty()) return false;
        frame = std::move(frames_.front());
        frames_.pop_front();
        not_full_.notify_one();
        return true;
    }

    void finish() {
        std::lock_guard<std::mutex> lock(mutex_);
        finished_ = true;
        not_empty_.notify_all();
        not_full_.notify_all();
    }

    void cancel() {
        std::lock_guard<std::mutex> lock(mutex_);
        cancelled_ = true;
        not_empty_.notify_all();
        not_full_.notify_all();
    }

private:
    const size_t capacity_;
    std::mutex mutex_;
    std::condition_variable not_empty_;
    std::condition_variable not_full_;
    std::deque<PreparedNativeFrame> frames_;
    bool finished_ = false;
    bool cancelled_ = false;
};

class OrderedInferenceQueue {
public:
    OrderedInferenceQueue(size_t capacity, int worker_count)
        : capacity_(capacity), worker_count_(worker_count) {}

    bool push(InferredNativeFrame&& frame) {
        const int sequence_index = frame.frame.sequence_index;
        std::unique_lock<std::mutex> lock(mutex_);
        not_full_.wait(lock, [this, sequence_index]() {
            return cancelled_ ||
                frames_.size() < capacity_ ||
                sequence_index == next_sequence_index_;
        });
        if (cancelled_) return false;
        const auto inserted = frames_.emplace(
            sequence_index,
            std::move(frame)
        );
        if (!inserted.second) {
            cancelled_ = true;
            not_empty_.notify_all();
            not_full_.notify_all();
            return false;
        }
        not_empty_.notify_one();
        return true;
    }

    bool pop_next(InferredNativeFrame& frame) {
        std::unique_lock<std::mutex> lock(mutex_);
        not_empty_.wait(lock, [this]() {
            return cancelled_ ||
                frames_.find(next_sequence_index_) != frames_.end() ||
                workers_finished_ == worker_count_;
        });
        if (cancelled_) return false;
        auto next = frames_.find(next_sequence_index_);
        if (next == frames_.end()) return false;
        frame = std::move(next->second);
        frames_.erase(next);
        ++next_sequence_index_;
        not_full_.notify_all();
        return true;
    }

    void worker_finished() {
        std::lock_guard<std::mutex> lock(mutex_);
        ++workers_finished_;
        not_empty_.notify_all();
        not_full_.notify_all();
    }

    void cancel() {
        std::lock_guard<std::mutex> lock(mutex_);
        cancelled_ = true;
        not_empty_.notify_all();
        not_full_.notify_all();
    }

private:
    const size_t capacity_;
    const int worker_count_;
    std::mutex mutex_;
    std::condition_variable not_empty_;
    std::condition_variable not_full_;
    std::map<int, InferredNativeFrame> frames_;
    int next_sequence_index_ = 0;
    int workers_finished_ = 0;
    bool cancelled_ = false;
};

struct InferenceWorkerStats {
    std::chrono::nanoseconds inference_time{0};
    int frames_inferred = 0;
    std::string error;
};

struct DecodePreprocessStats {
    int frames_prepared = 0;
    bool eos_reached = false;
    std::string error;
    std::chrono::nanoseconds decoder_time{0};
    std::chrono::nanoseconds preprocessing_time{0};
    std::chrono::nanoseconds queue_wait_time{0};
};

void run_inference_worker(
    ncnn::Net& net,
    PreparedFrameQueue& prepared_frames,
    OrderedInferenceQueue& inferred_frames,
    ncnn::UnlockedPoolAllocator& blob_pool_allocator,
    ncnn::PoolAllocator& workspace_pool_allocator,
    InferenceWorkerStats& stats
) {
    while (true) {
        PreparedNativeFrame frame;
        if (!prepared_frames.pop(frame)) break;

        const auto inference_start = std::chrono::steady_clock::now();
        ncnn::Extractor extractor = net.create_extractor();
        extractor.set_blob_allocator(&blob_pool_allocator);
        extractor.set_workspace_allocator(&workspace_pool_allocator);
        const int input_status = extractor.input("in0", frame.input);
        ncnn::Mat output;
        const int extract_status = input_status == 0
            ? extractor.extract("out0", output)
            : input_status;
        const auto inference_duration =
            std::chrono::steady_clock::now() - inference_start;
        stats.inference_time += inference_duration;
        if (input_status != 0 || extract_status != 0 || output.empty()) {
            stats.error =
                "NCNN failed in a parallel inference worker on source frame " +
                std::to_string(frame.source_frame_index) + ".";
            prepared_frames.cancel();
            inferred_frames.cancel();
            break;
        }

        InferredNativeFrame inferred_frame;
        inferred_frame.frame = std::move(frame);
        inferred_frame.output = std::move(output);
        inferred_frame.inference_time_ns =
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                inference_duration
            ).count();
        if (!inferred_frames.push(std::move(inferred_frame))) break;
        ++stats.frames_inferred;
    }
    inferred_frames.worker_finished();
}

bool warm_up_network(
    ncnn::Net& net,
    int input_size,
    int repetitions,
    std::string& error
) {
    ncnn::Mat input(input_size, input_size, 3);
    if (input.empty()) {
        error = "NCNN could not allocate its warm-up input.";
        return false;
    }
    input.fill(114.0f / 255.0f);

    ncnn::UnlockedPoolAllocator blob_pool_allocator;
    ncnn::PoolAllocator workspace_pool_allocator;
    blob_pool_allocator.set_size_compare_ratio(0.0f);
    workspace_pool_allocator.set_size_compare_ratio(0.0f);
    for (int index = 0; index < repetitions; ++index) {
        ncnn::Extractor extractor = net.create_extractor();
        extractor.set_blob_allocator(&blob_pool_allocator);
        extractor.set_workspace_allocator(&workspace_pool_allocator);
        const int input_status = extractor.input("in0", input);
        ncnn::Mat output;
        const int extract_status = input_status == 0
            ? extractor.extract("out0", output)
            : input_status;
        if (input_status != 0 || extract_status != 0 || output.empty()) {
            error = "NCNN failed while warming the selected backend.";
            return false;
        }
    }
    return true;
}

void produce_preprocessed_frames(
    VideoSource& source,
    AMediaCodec* decoder,
    AImageReader* reader,
    ImageSignal& image_signal,
    int input_size,
    int max_frames,
    int frame_stride,
    bool retain_i420,
    const ncnn::Option& preprocessing_option,
    PreparedFrameQueue& queue,
    DecodePreprocessStats& stats
);

void run_inference_worker(
    ncnn::Net& net,
    PreparedFrameQueue& prepared_frames,
    OrderedInferenceQueue& inferred_frames,
    ncnn::UnlockedPoolAllocator& blob_pool_allocator,
    ncnn::PoolAllocator& workspace_pool_allocator,
    InferenceWorkerStats& stats
);

bool decode_detection_output(
    const ncnn::Mat& output,
    int source_width,
    int source_height,
    int input_size,
    int configured_class_count,
    bool is_pose,
    float confidence_threshold,
    float iou_threshold,
    int detection_count,
    int output_format,
    int coordinate_format,
    std::vector<NativeDetection>& detections,
    std::string& error
);

bool dispatch_frame_callback(
    JNIEnv* env,
    jobject callback,
    jmethodID callback_method,
    int frame_index,
    const PreparedNativeFrame& frame,
    int source_width,
    int source_height,
    int64_t inference_time_ns,
    int64_t postprocessing_time_ns,
    std::vector<NativeDetection>& detections,
    std::string& error
);

jobject make_ncnn_pipeline_benchmark(
    JNIEnv* env,
    const VideoSource& source,
    int frames_processed,
    int frames_requested,
    int threads,
    int workers,
    int input_size,
    int total_detections,
    int frames_with_detections,
    int frames_encoded,
    int64_t wall_time_ms,
    int64_t decoder_time_ms,
    int64_t preprocessing_time_ms,
    int64_t inference_time_ms,
    int64_t output_time_ms,
    int64_t postprocessing_time_ms,
    int64_t annotation_time_ms,
    int64_t encoding_time_ms,
    double pipeline_fps,
    double inference_fps,
    bool use_vulkan,
    bool callback_used,
    const std::string& decoder_name,
    bool eos_reached
);

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_integrapose_mobile_offline_NativeMediaPipeline_nativeBenchmarkNcnn(
    JNIEnv* env,
    jclass,
    jint fd,
    jlong offset,
    jlong length,
    jstring param_path_value,
    jstring weights_path_value,
    jint requested_input_size,
    jint requested_threads,
    jint requested_workers,
    jboolean request_vulkan,
    jint max_frames,
    jint requested_frame_stride,
    jint requested_class_count,
    jboolean is_pose_value,
    jfloat confidence_threshold_value,
    jfloat iou_threshold_value,
    jint requested_detection_count,
    jint output_format_value,
    jint coordinate_format_value,
    jint box_color_argb_value,
    jint keypoint_color_argb_value,
    jintArray skeleton_connections_value,
    jfloatArray roi_coordinates_value,
    jobjectArray roi_names_value,
    jintArray roi_colors_value,
    jint roi_label_size_value,
    jstring annotated_video_path_value,
    jobject stop_signal,
    jobject frame_callback,
    jstring decoder_name_value
) {
    const std::string param_path = read_utf8(env, param_path_value);
    const std::string weights_path = read_utf8(env, weights_path_value);
    const std::string annotated_video_path = read_utf8(
        env,
        annotated_video_path_value
    );
    const std::string decoder_name = read_utf8(env, decoder_name_value);
    const bool encode_video = !annotated_video_path.empty();
    const int input_size = static_cast<int>(requested_input_size);
    const int threads = std::max(
        1,
        std::min(8, static_cast<int>(requested_threads))
    );
    const bool use_vulkan = request_vulkan == JNI_TRUE;
    const int inference_workers = static_cast<int>(requested_workers);
    const int frame_stride = static_cast<int>(requested_frame_stride);
    const int configured_class_count = std::max(
        0,
        static_cast<int>(requested_class_count)
    );
    const bool is_pose = is_pose_value == JNI_TRUE;
    const float confidence_threshold =
        static_cast<float>(confidence_threshold_value);
    const float iou_threshold = static_cast<float>(iou_threshold_value);
    const int detection_count = static_cast<int>(requested_detection_count);
    const int output_format = static_cast<int>(output_format_value);
    const int coordinate_format = static_cast<int>(coordinate_format_value);
    const int roi_label_size = static_cast<int>(roi_label_size_value);
    const uint32_t box_argb = static_cast<uint32_t>(box_color_argb_value);
    const uint32_t keypoint_argb =
        static_cast<uint32_t>(keypoint_color_argb_value);
    const integrapose::media::AnnotationColors annotation_colors{
        static_cast<uint8_t>((box_argb >> 16u) & 0xffu),
        static_cast<uint8_t>((box_argb >> 8u) & 0xffu),
        static_cast<uint8_t>(box_argb & 0xffu),
        static_cast<uint8_t>((box_argb >> 24u) & 0xffu),
        static_cast<uint8_t>((keypoint_argb >> 16u) & 0xffu),
        static_cast<uint8_t>((keypoint_argb >> 8u) & 0xffu),
        static_cast<uint8_t>(keypoint_argb & 0xffu),
        static_cast<uint8_t>((keypoint_argb >> 24u) & 0xffu)
    };
    std::vector<integrapose::media::SkeletonEdge> skeleton_edges;
    if (skeleton_connections_value != nullptr) {
        const jsize value_count = env->GetArrayLength(skeleton_connections_value);
        if (value_count < 0 || value_count > 1024 || value_count % 2 != 0) {
            throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "Skeleton connections must contain pairs of keypoint indices."
            );
            return nullptr;
        }
        std::vector<jint> flat_edges(static_cast<size_t>(value_count));
        if (value_count > 0) {
            env->GetIntArrayRegion(
                skeleton_connections_value,
                0,
                value_count,
                flat_edges.data()
            );
            if (env->ExceptionCheck()) return nullptr;
        }
        skeleton_edges.reserve(static_cast<size_t>(value_count / 2));
        for (jsize index = 0; index < value_count; index += 2) {
            const int start = static_cast<int>(flat_edges[static_cast<size_t>(index)]);
            const int end = static_cast<int>(flat_edges[static_cast<size_t>(index + 1)]);
            if (start < 0 || end < 0 || start > 10000 || end > 10000 || start == end) {
                throw_java(
                    env,
                    "java/lang/IllegalArgumentException",
                    "Skeleton connections contain an invalid keypoint index."
                );
                return nullptr;
            }
            skeleton_edges.push_back({start, end});
        }
    }
    std::vector<integrapose::media::OverlayRoi> overlay_rois;
    if (roi_coordinates_value != nullptr || roi_names_value != nullptr ||
        roi_colors_value != nullptr) {
        if (roi_coordinates_value == nullptr || roi_names_value == nullptr ||
            roi_colors_value == nullptr) {
            throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "ROI coordinates, names, and colors must be provided together."
            );
            return nullptr;
        }
        const jsize coordinate_count =
            env->GetArrayLength(roi_coordinates_value);
        const jsize name_count = env->GetArrayLength(roi_names_value);
        const jsize color_count = env->GetArrayLength(roi_colors_value);
        if (coordinate_count < 0 || coordinate_count > 1024 ||
            coordinate_count % 4 != 0 ||
            name_count != coordinate_count / 4 ||
            color_count != name_count) {
            throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "ROI coordinates must contain four values and one color per ROI name."
            );
            return nullptr;
        }
        std::vector<jfloat> flat_coordinates(
            static_cast<size_t>(coordinate_count)
        );
        if (coordinate_count > 0) {
            env->GetFloatArrayRegion(
                roi_coordinates_value,
                0,
                coordinate_count,
                flat_coordinates.data()
            );
            if (env->ExceptionCheck()) return nullptr;
        }
        std::vector<jint> flat_colors(static_cast<size_t>(color_count));
        if (color_count > 0) {
            env->GetIntArrayRegion(
                roi_colors_value,
                0,
                color_count,
                flat_colors.data()
            );
            if (env->ExceptionCheck()) return nullptr;
        }
        overlay_rois.reserve(static_cast<size_t>(name_count));
        for (jsize index = 0; index < name_count; ++index) {
            const size_t offset = static_cast<size_t>(index) * 4u;
            const float left = flat_coordinates[offset];
            const float top = flat_coordinates[offset + 1u];
            const float right = flat_coordinates[offset + 2u];
            const float bottom = flat_coordinates[offset + 3u];
            if (!std::isfinite(left) || !std::isfinite(top) ||
                !std::isfinite(right) || !std::isfinite(bottom)) {
                throw_java(
                    env,
                    "java/lang/IllegalArgumentException",
                    "ROI coordinates must be finite normalized values."
                );
                return nullptr;
            }
            jstring name_value = static_cast<jstring>(
                env->GetObjectArrayElement(roi_names_value, index)
            );
            if (env->ExceptionCheck()) return nullptr;
            std::string name = read_utf8(env, name_value);
            if (name_value != nullptr) env->DeleteLocalRef(name_value);
            if (name.empty()) name = "ROI";
            if (name.size() > 64u) name.resize(64u);
            const uint32_t roi_argb = static_cast<uint32_t>(
                flat_colors[static_cast<size_t>(index)]
            );
            overlay_rois.push_back({
                left,
                top,
                right,
                bottom,
                name,
                static_cast<uint8_t>((roi_argb >> 16u) & 0xffu),
                static_cast<uint8_t>((roi_argb >> 8u) & 0xffu),
                static_cast<uint8_t>(roi_argb & 0xffu),
                static_cast<uint8_t>((roi_argb >> 24u) & 0xffu)
            });
        }
    }
    if (param_path.empty() ||
        weights_path.empty() ||
        input_size < 32 ||
        input_size > 2048 ||
        input_size % 32 != 0 ||
        inference_workers < 1 ||
        inference_workers > 4 ||
        (use_vulkan && inference_workers != 1) ||
        max_frames < 0 ||
        frame_stride < 1 ||
        frame_stride > 100000 ||
        !std::isfinite(confidence_threshold) ||
        confidence_threshold < 0.0f ||
        confidence_threshold > 1.0f ||
        !std::isfinite(iou_threshold) ||
        roi_label_size < 0 ||
        roi_label_size > 3 ||
        iou_threshold < 0.0f ||
        iou_threshold > 1.0f ||
        detection_count <= 0 ||
        detection_count > 5000 ||
        output_format < 0 ||
        output_format > 2 ||
        coordinate_format < 0 ||
        coordinate_format > 2) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "The native NCNN pipeline arguments are invalid."
        );
        return nullptr;
    }

    jmethodID stop_requested_method = nullptr;
    if (stop_signal != nullptr) {
        jclass stop_signal_class = env->GetObjectClass(stop_signal);
        if (stop_signal_class == nullptr) return nullptr;
        stop_requested_method = env->GetMethodID(
            stop_signal_class,
            "isStopRequested",
            "()Z"
        );
        if (stop_requested_method == nullptr) return nullptr;
        env->DeleteLocalRef(stop_signal_class);
    }

    jmethodID frame_callback_method = nullptr;
    if (frame_callback != nullptr) {
        jclass callback_class = env->GetObjectClass(frame_callback);
        if (callback_class == nullptr) return nullptr;
        frame_callback_method = env->GetMethodID(
            callback_class,
            "onNativeFrame",
            "(IJIIJJJ[I[F[F[I[F)[I"
        );
        if (frame_callback_method == nullptr) return nullptr;
        env->DeleteLocalRef(callback_class);
    }

    VideoSource source;
    std::string error;
    if (!open_video_source(fd, offset, length, source, error)) {
        throw_java(env, "java/lang/IllegalArgumentException", error);
        return nullptr;
    }

    ncnn::PoolAllocator workspace_pool_allocator;
    workspace_pool_allocator.set_size_compare_ratio(0.0f);
    ncnn::set_omp_dynamic(0);
    ncnn::set_omp_num_threads(threads);
    ncnn::Net net;
    net.opt.num_threads = threads;
    net.opt.blob_allocator = nullptr;
    net.opt.workspace_allocator = &workspace_pool_allocator;
    net.opt.lightmode = true;
    net.opt.use_packing_layout = true;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;
    if (use_vulkan) {
#if NCNN_VULKAN
        if (integrapose::ncnn_runtime::gpu_count() <= 0) {
            throw_java(
                env,
                "java/lang/IllegalStateException",
                "NCNN Vulkan was requested for the native video pipeline, but no compatible compute device is available."
            );
            return nullptr;
        }
        net.set_vulkan_device(0);
        net.opt.use_vulkan_compute = true;
#else
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "This NCNN native video library was built without Vulkan support."
        );
        return nullptr;
#endif
    }
    const int param_status = net.load_param(param_path.c_str());
    const int model_status = param_status == 0
        ? net.load_model(weights_path.c_str())
        : -1;
    if (param_status != 0 || model_status != 0) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "NCNN could not load the benchmark model."
        );
        return nullptr;
    }
    if (!warm_up_network(net, input_size, 2, error)) {
        throw_java(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }

    AImageReader* raw_reader = nullptr;
    const media_status_t reader_status = AImageReader_newWithUsage(
        source.width,
        source.height,
        AIMAGE_FORMAT_YUV_420_888,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        4,
        &raw_reader
    );
    ReaderPtr reader(raw_reader);
    if (reader_status != AMEDIA_OK || !reader) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native NCNN pipeline could not create a YUV decoder surface."
        );
        return nullptr;
    }

    ImageSignal image_signal;
    AImageReader_ImageListener listener{
        &image_signal,
        on_image_available
    };
    if (AImageReader_setImageListener(reader.get(), &listener) != AMEDIA_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native NCNN pipeline could not register its frame listener."
        );
        return nullptr;
    }
    ANativeWindow* output_window = nullptr;
    if (AImageReader_getWindow(reader.get(), &output_window) != AMEDIA_OK ||
        output_window == nullptr) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native NCNN pipeline could not obtain its decoder surface."
        );
        return nullptr;
    }

    CodecGuard decoder;
    decoder.value = decoder_name.empty()
        ? AMediaCodec_createDecoderByType(source.mime.c_str())
        : AMediaCodec_createCodecByName(decoder_name.c_str());
    if (decoder.value == nullptr ||
        AMediaCodec_configure(
            decoder.value,
            source.format.get(),
            output_window,
            nullptr,
            0
        ) != AMEDIA_OK ||
        AMediaCodec_start(decoder.value) != AMEDIA_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native NCNN pipeline could not start the video decoder."
        );
        return nullptr;
    }
    decoder.started = true;

    ncnn::Option preprocessing_option;
    preprocessing_option.num_threads = 1;
    preprocessing_option.lightmode = true;
    PreparedFrameQueue prepared_frames(
        std::max<size_t>(3, static_cast<size_t>(inference_workers + 1))
    );
    DecodePreprocessStats producer_stats;
    std::vector<std::unique_ptr<ncnn::UnlockedPoolAllocator>>
        inference_blob_allocators;
    std::vector<std::unique_ptr<ncnn::PoolAllocator>>
        inference_workspace_allocators;
    inference_blob_allocators.reserve(inference_workers);
    inference_workspace_allocators.reserve(inference_workers);
    for (int index = 0; index < inference_workers; ++index) {
        auto blob_allocator =
            std::make_unique<ncnn::UnlockedPoolAllocator>();
        blob_allocator->set_size_compare_ratio(0.0f);
        inference_blob_allocators.push_back(std::move(blob_allocator));
        auto workspace_allocator =
            std::make_unique<ncnn::PoolAllocator>();
        workspace_allocator->set_size_compare_ratio(0.0f);
        inference_workspace_allocators.push_back(
            std::move(workspace_allocator)
        );
    }
    OrderedInferenceQueue inferred_frames(
        std::max<size_t>(4, static_cast<size_t>(inference_workers + 2)),
        inference_workers
    );
    std::vector<InferenceWorkerStats> inference_worker_stats(
        inference_workers
    );
    integrapose::media::AnnotatedFrameQueue annotated_frames(3);
    integrapose::media::EncoderStats encoder_stats;
    const auto pipeline_start = std::chrono::steady_clock::now();
    std::thread encoder_thread;
    if (encode_video) {
        encoder_thread = std::thread(
            integrapose::media::encode_annotated_frames,
            std::cref(annotated_video_path),
            source.width,
            source.height,
            source.rotation_degrees,
            source.frame_rate,
            std::ref(annotated_frames),
            std::ref(encoder_stats)
        );
    }
    std::vector<std::thread> inference_threads;
    inference_threads.reserve(inference_workers);
    for (int index = 0; index < inference_workers; ++index) {
        inference_threads.emplace_back(
            run_inference_worker,
            std::ref(net),
            std::ref(prepared_frames),
            std::ref(inferred_frames),
            std::ref(*inference_blob_allocators[index]),
            std::ref(*inference_workspace_allocators[index]),
            std::ref(inference_worker_stats[index])
        );
    }
    std::thread producer(
        produce_preprocessed_frames,
        std::ref(source),
        decoder.value,
        reader.get(),
        std::ref(image_signal),
        input_size,
        max_frames,
        frame_stride,
        encode_video,
        std::cref(preprocessing_option),
        std::ref(prepared_frames),
        std::ref(producer_stats)
    );

    std::chrono::nanoseconds inference_time(0);
    std::chrono::nanoseconds output_time(0);
    std::chrono::nanoseconds postprocessing_time(0);
    std::chrono::nanoseconds annotation_time(0);
    std::chrono::nanoseconds annotation_queue_wait_time(0);
    int frames_processed = 0;
    int total_detections = 0;
    int frames_with_detections = 0;
    bool graceful_stop_requested = false;
    std::vector<NativeDetection> detections;

    while (max_frames == 0 || frames_processed < max_frames) {
        if (stop_signal != nullptr && stop_requested_method != nullptr) {
            const jboolean should_stop = env->CallBooleanMethod(
                stop_signal,
                stop_requested_method
            );
            if (env->ExceptionCheck()) {
                error = "The Kotlin native-pipeline stop check failed.";
                break;
            }
            if (should_stop == JNI_TRUE) {
                graceful_stop_requested = true;
                break;
            }
        }
        InferredNativeFrame inferred_frame;
        if (!inferred_frames.pop_next(inferred_frame)) break;
        PreparedNativeFrame& frame = inferred_frame.frame;
        ncnn::Mat& output = inferred_frame.output;
        const auto inference_duration = std::chrono::nanoseconds(
            inferred_frame.inference_time_ns
        );
        inference_time += inference_duration;

        const auto output_start = std::chrono::steady_clock::now();
        ncnn::Mat float_output = output;
        if (float_output.elembits() == 16) {
            ncnn::Mat converted;
            ncnn::cast_float16_to_float32(
                float_output,
                converted,
                net.opt
            );
            float_output = converted;
        }
        if (float_output.elempack != 1) {
            ncnn::Mat unpacked;
            ncnn::convert_packing(float_output, unpacked, 1, net.opt);
            float_output = unpacked;
        }
        if (float_output.empty() ||
            float_output.elembits() != 32 ||
            float_output.dims != 2) {
            error = "NCNN returned an unsupported pipelined output layout.";
            break;
        }
        const auto output_end = std::chrono::steady_clock::now();
        output_time += output_end - output_start;

        const auto postprocessing_start =
            std::chrono::steady_clock::now();
        if (!decode_detection_output(
                float_output,
                source.width,
                source.height,
                input_size,
                configured_class_count,
                is_pose,
                confidence_threshold,
                iou_threshold,
                detection_count,
                output_format,
                coordinate_format,
                detections,
                error
            )) {
            break;
        }
        const auto postprocessing_duration =
            std::chrono::steady_clock::now() - postprocessing_start;
        postprocessing_time += postprocessing_duration;
        if (!dispatch_frame_callback(
                env,
                frame_callback,
                frame_callback_method,
                frame.source_frame_index,
                frame,
                source.width,
                source.height,
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    inference_duration
                ).count(),
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    postprocessing_duration
                ).count(),
                detections,
                error
            )) {
            break;
        }
        if (encode_video) {
            const auto annotation_start = std::chrono::steady_clock::now();
            if (!integrapose::media::draw_annotations_i420(
                    frame.i420,
                    source.width,
                    source.height,
                    detections,
                    overlay_rois,
                    annotation_colors,
                    skeleton_edges,
                    roi_label_size,
                    error
                )) {
                break;
            }
            annotation_time +=
                std::chrono::steady_clock::now() - annotation_start;
            integrapose::media::AnnotatedI420Frame annotated_frame;
            annotated_frame.i420 = std::move(frame.i420);
            annotated_frame.presentation_time_us = frame.presentation_time_us;
            if (!annotated_frames.push(
                    std::move(annotated_frame),
                    annotation_queue_wait_time
                )) {
                error = "The native H.264 encoder stopped accepting frames.";
                break;
            }
        }
        total_detections += static_cast<int>(detections.size());
        if (!detections.empty()) ++frames_with_detections;
        ++frames_processed;
    }

    prepared_frames.cancel();
    inferred_frames.cancel();
    if (producer.joinable()) producer.join();
    for (auto& inference_thread : inference_threads) {
        if (inference_thread.joinable()) inference_thread.join();
    }
    if (error.empty() && !producer_stats.error.empty()) {
        error = producer_stats.error;
    }
    int frames_inferred = 0;
    for (const auto& worker_stats : inference_worker_stats) {
        frames_inferred += worker_stats.frames_inferred;
        if (error.empty() && !worker_stats.error.empty()) {
            error = worker_stats.error;
        }
    }
    if (error.empty() && !graceful_stop_requested &&
        frames_inferred != frames_processed) {
        error =
            "The ordered NCNN inference stage did not return every frame.";
    }
    if (encode_video) {
        const bool stopped_before_first_frame =
            graceful_stop_requested && frames_processed == 0;
        if (error.empty() && !stopped_before_first_frame) {
            annotated_frames.finish();
        } else {
            annotated_frames.cancel();
        }
        if (encoder_thread.joinable()) encoder_thread.join();
        if (error.empty() && !stopped_before_first_frame &&
            !encoder_stats.error.empty()) {
            error = encoder_stats.error;
        }
        if (error.empty() && !stopped_before_first_frame &&
            (!encoder_stats.completed ||
             encoder_stats.frames_encoded != frames_processed)) {
            error =
                "The native annotated MP4 did not contain every analyzed frame.";
        }
    }

    const auto pipeline_end = std::chrono::steady_clock::now();
    if (!error.empty()) {
        if (!env->ExceptionCheck()) {
            throw_java(env, "java/lang/IllegalStateException", error);
        }
        return nullptr;
    }
    const auto wall_time = pipeline_end - pipeline_start;
    const auto preprocessing_time = producer_stats.preprocessing_time;
    const auto decoder_time = producer_stats.decoder_time;
    const bool output_eos = producer_stats.eos_reached;
    const double wall_seconds =
        std::chrono::duration<double>(wall_time).count();
    const double inference_seconds =
        std::chrono::duration<double>(inference_time).count();
    const double pipeline_fps = wall_seconds > 0.0
        ? static_cast<double>(frames_processed) / wall_seconds
        : 0.0;
    const double inference_fps = inference_seconds > 0.0
        ? static_cast<double>(frames_processed * inference_workers) /
            inference_seconds
        : 0.0;
    return make_ncnn_pipeline_benchmark(
        env,
        source,
        frames_processed,
        max_frames,
        threads,
        inference_workers,
        input_size,
        total_detections,
        frames_with_detections,
        encoder_stats.frames_encoded,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            wall_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            decoder_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            preprocessing_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            inference_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            output_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            postprocessing_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            annotation_time
        ).count(),
        std::chrono::duration_cast<std::chrono::milliseconds>(
            encoder_stats.color_conversion_time + encoder_stats.codec_time
        ).count(),
        pipeline_fps,
        inference_fps,
        use_vulkan,
        frame_callback != nullptr,
        decoder_name,
        output_eos
    );
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_integrapose_mobile_offline_NativeMediaPipeline_nativeBenchmarkDecode(
    JNIEnv* env,
    jclass,
    jint fd,
    jlong offset,
    jlong length,
    jint max_frames,
    jstring decoder_name_value
) {
    const std::string decoder_name = read_utf8(env, decoder_name_value);
    if (max_frames < 0) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "The native decode frame limit cannot be negative."
        );
        return nullptr;
    }

    VideoSource source;
    std::string error;
    if (!open_video_source(fd, offset, length, source, error)) {
        throw_java(env, "java/lang/IllegalArgumentException", error);
        return nullptr;
    }

    AImageReader* raw_reader = nullptr;
    const media_status_t reader_status = AImageReader_newWithUsage(
        source.width,
        source.height,
        AIMAGE_FORMAT_YUV_420_888,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        4,
        &raw_reader
    );
    ReaderPtr reader(raw_reader);
    if (reader_status != AMEDIA_OK || !reader) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The device could not create a CPU-readable YUV decoder surface (error " +
                std::to_string(reader_status) + ")."
        );
        return nullptr;
    }

    ImageSignal image_signal;
    AImageReader_ImageListener listener{
        &image_signal,
        on_image_available
    };
    const media_status_t listener_status = AImageReader_setImageListener(
        reader.get(),
        &listener
    );
    if (listener_status != AMEDIA_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native decoder could not register its frame listener."
        );
        return nullptr;
    }

    ANativeWindow* output_window = nullptr;
    if (AImageReader_getWindow(reader.get(), &output_window) != AMEDIA_OK ||
        output_window == nullptr) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native decoder could not obtain its output surface."
        );
        return nullptr;
    }

    CodecGuard decoder;
    decoder.value = decoder_name.empty()
        ? AMediaCodec_createDecoderByType(source.mime.c_str())
        : AMediaCodec_createCodecByName(decoder_name.c_str());
    if (decoder.value == nullptr) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "No native decoder is available for " + source.mime + "."
        );
        return nullptr;
    }
    const media_status_t configure_status = AMediaCodec_configure(
        decoder.value,
        source.format.get(),
        output_window,
        nullptr,
        0
    );
    if (configure_status != AMEDIA_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native video decoder could not be configured (error " +
                std::to_string(configure_status) + ")."
        );
        return nullptr;
    }
    const media_status_t start_status = AMediaCodec_start(decoder.value);
    if (start_status != AMEDIA_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "The native video decoder could not start (error " +
                std::to_string(start_status) + ")."
        );
        return nullptr;
    }
    decoder.started = true;

    const auto start_time = std::chrono::steady_clock::now();
    bool input_eos = false;
    bool output_eos = false;
    int frames_decoded = 0;
    int idle_polls = 0;

    while (!output_eos && (max_frames == 0 || frames_decoded < max_frames)) {
        bool made_progress = false;

        if (!input_eos) {
            const ssize_t input_index = AMediaCodec_dequeueInputBuffer(
                decoder.value,
                0
            );
            if (input_index >= 0) {
                size_t capacity = 0;
                uint8_t* input_buffer = AMediaCodec_getInputBuffer(
                    decoder.value,
                    static_cast<size_t>(input_index),
                    &capacity
                );
                if (input_buffer == nullptr || capacity == 0) {
                    error = "The native decoder returned an invalid input buffer.";
                    break;
                }
                const ssize_t sample_size = AMediaExtractor_readSampleData(
                    source.extractor.get(),
                    input_buffer,
                    capacity
                );
                if (sample_size < 0) {
                    const media_status_t queue_status = AMediaCodec_queueInputBuffer(
                        decoder.value,
                        static_cast<size_t>(input_index),
                        0,
                        0,
                        0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                    );
                    if (queue_status != AMEDIA_OK) {
                        error = "The native decoder could not queue end-of-stream.";
                        break;
                    }
                    input_eos = true;
                } else {
                    int64_t presentation_time_us = AMediaExtractor_getSampleTime(
                        source.extractor.get()
                    );
                    if (presentation_time_us < 0) presentation_time_us = 0;
                    const media_status_t queue_status = AMediaCodec_queueInputBuffer(
                        decoder.value,
                        static_cast<size_t>(input_index),
                        0,
                        static_cast<size_t>(sample_size),
                        presentation_time_us,
                        0
                    );
                    if (queue_status != AMEDIA_OK) {
                        error = "The native decoder rejected a compressed video sample.";
                        break;
                    }
                    AMediaExtractor_advance(source.extractor.get());
                }
                made_progress = true;
            }
        }

        AMediaCodecBufferInfo output_info{};
        const ssize_t output_index = AMediaCodec_dequeueOutputBuffer(
            decoder.value,
            &output_info,
            kCodecTimeoutUs
        );
        if (output_index >= 0) {
            const bool render = output_info.size > 0;
            const bool is_eos =
                (output_info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            const media_status_t release_status = AMediaCodec_releaseOutputBuffer(
                decoder.value,
                static_cast<size_t>(output_index),
                render
            );
            if (release_status != AMEDIA_OK) {
                error = "The native decoder could not release a decoded frame.";
                break;
            }
            if (render) {
                ImagePtr image;
                if (!acquire_decoded_image(
                        reader.get(),
                        image_signal,
                        image,
                        error
                    ) ||
                    !validate_yuv_image(
                        image.get(),
                        source.width,
                        source.height,
                        error
                    )) {
                    break;
                }
                ++frames_decoded;
            }
            output_eos = is_eos;
            made_progress = true;
        } else if (output_index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
                   output_index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            made_progress = true;
        }

        if (made_progress) {
            idle_polls = 0;
        } else if (++idle_polls > kMaxIdlePolls) {
            error = "The native video decoder stopped making progress.";
            break;
        }
    }

    const auto end_time = std::chrono::steady_clock::now();
    if (!error.empty()) {
        throw_java(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }
    const double elapsed_seconds =
        std::chrono::duration<double>(end_time - start_time).count();
    const int64_t wall_time_ms = std::chrono::duration_cast<
        std::chrono::milliseconds
    >(end_time - start_time).count();
    const double decode_fps = elapsed_seconds > 0.0
        ? static_cast<double>(frames_decoded) / elapsed_seconds
        : 0.0;
    return make_decode_benchmark(
        env,
        source,
        frames_decoded,
        max_frames,
        wall_time_ms,
        decode_fps,
        decoder_name,
        output_eos
    );
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_integrapose_mobile_offline_NativeMediaPipeline_nativeProbe(
    JNIEnv* env,
    jclass,
    jint fd,
    jlong offset,
    jlong length
) {
    VideoSource source;
    std::string error;
    if (!open_video_source(fd, offset, length, source, error)) {
        throw_java(env, "java/lang/IllegalArgumentException", error);
        return nullptr;
    }
    return make_video_info(env, source);
}

namespace {

void on_image_available(void* context, AImageReader*) {
    auto* signal = static_cast<ImageSignal*>(context);
    if (signal == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(signal->mutex);
        ++signal->generation;
    }
    signal->available.notify_one();
}

bool acquire_decoded_image(
    AImageReader* reader,
    ImageSignal& signal,
    ImagePtr& image,
    std::string& error
) {
    for (int attempt = 0; attempt < 20; ++attempt) {
        AImage* raw_image = nullptr;
        const media_status_t status = AImageReader_acquireNextImage(
            reader,
            &raw_image
        );
        if (status == AMEDIA_OK && raw_image != nullptr) {
            image.reset(raw_image);
            return true;
        }
        if (status != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
            error = "The native decoder could not acquire its YUV frame (error " +
                std::to_string(status) + ").";
            return false;
        }
        std::unique_lock<std::mutex> lock(signal.mutex);
        const uint64_t observed_generation = signal.generation;
        signal.available.wait_for(
            lock,
            std::chrono::milliseconds(50),
            [&signal, observed_generation]() {
                return signal.generation != observed_generation;
            }
        );
    }
    error = "Timed out waiting for a CPU-readable decoded video frame.";
    return false;
}

bool validate_yuv_image(
    const AImage* image,
    int32_t expected_width,
    int32_t expected_height,
    std::string& error
) {
    int32_t width = 0;
    int32_t height = 0;
    int32_t plane_count = 0;
    if (AImage_getWidth(image, &width) != AMEDIA_OK ||
        AImage_getHeight(image, &height) != AMEDIA_OK ||
        AImage_getNumberOfPlanes(image, &plane_count) != AMEDIA_OK) {
        error = "The native decoder returned unreadable frame metadata.";
        return false;
    }
    if (width != expected_width || height != expected_height || plane_count < 3) {
        error = "The native decoder returned an unexpected YUV frame layout.";
        return false;
    }
    for (int plane = 0; plane < 3; ++plane) {
        int32_t row_stride = 0;
        int32_t pixel_stride = 0;
        uint8_t* data = nullptr;
        int data_length = 0;
        if (AImage_getPlaneRowStride(image, plane, &row_stride) != AMEDIA_OK ||
            AImage_getPlanePixelStride(image, plane, &pixel_stride) != AMEDIA_OK ||
            AImage_getPlaneData(image, plane, &data, &data_length) != AMEDIA_OK ||
            row_stride <= 0 ||
            pixel_stride <= 0 ||
            data == nullptr ||
            data_length <= 0) {
            error = "The native decoder returned an inaccessible YUV plane.";
            return false;
        }
        volatile uint8_t first_value = data[0];
        (void)first_value;
    }
    return true;
}

struct YuvPlaneView {
    uint8_t* data = nullptr;
    int length = 0;
    int row_stride = 0;
    int pixel_stride = 0;
};

bool get_yuv_plane(
    const AImage* image,
    int plane_index,
    YuvPlaneView& plane,
    std::string& error
) {
    if (AImage_getPlaneData(
            image,
            plane_index,
            &plane.data,
            &plane.length
        ) != AMEDIA_OK ||
        AImage_getPlaneRowStride(
            image,
            plane_index,
            &plane.row_stride
        ) != AMEDIA_OK ||
        AImage_getPlanePixelStride(
            image,
            plane_index,
            &plane.pixel_stride
        ) != AMEDIA_OK ||
        plane.data == nullptr ||
        plane.length <= 0 ||
        plane.row_stride <= 0 ||
        plane.pixel_stride <= 0) {
        error = "The native preprocessor could not read a YUV plane.";
        return false;
    }
    return true;
}

uint8_t clamp_byte(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}

bool yuv420_to_rgb(
    const AImage* image,
    int width,
    int height,
    std::vector<uint8_t>& rgb,
    std::vector<uint8_t>* i420,
    std::string& error
) {
    YuvPlaneView y_plane;
    YuvPlaneView u_plane;
    YuvPlaneView v_plane;
    if (!get_yuv_plane(image, 0, y_plane, error) ||
        !get_yuv_plane(image, 1, u_plane, error) ||
        !get_yuv_plane(image, 2, v_plane, error)) {
        return false;
    }
    rgb.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 3u);
    const size_t y_size =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t chroma_size = y_size / 4u;
    if (i420 != nullptr) {
        if (width % 2 != 0 || height % 2 != 0) {
            error = "Native annotated MP4 output requires even dimensions.";
            return false;
        }
        i420->resize(y_size + chroma_size * 2u);
    }
    for (int y = 0; y < height; ++y) {
        const int chroma_y = y / 2;
        for (int x = 0; x < width; ++x) {
            const int chroma_x = x / 2;
            const int y_offset = y * y_plane.row_stride + x * y_plane.pixel_stride;
            const int u_offset =
                chroma_y * u_plane.row_stride + chroma_x * u_plane.pixel_stride;
            const int v_offset =
                chroma_y * v_plane.row_stride + chroma_x * v_plane.pixel_stride;
            if (y_offset >= y_plane.length ||
                u_offset >= u_plane.length ||
                v_offset >= v_plane.length) {
                error = "A decoded YUV plane ended before the expected frame boundary.";
                return false;
            }

            const uint8_t y_value = y_plane.data[y_offset];
            const uint8_t u_value = u_plane.data[u_offset];
            const uint8_t v_value = v_plane.data[v_offset];
            if (i420 != nullptr) {
                const size_t compact_y_offset =
                    static_cast<size_t>(y) * static_cast<size_t>(width) +
                    static_cast<size_t>(x);
                (*i420)[compact_y_offset] = y_value;
                if (y % 2 == 0 && x % 2 == 0) {
                    const size_t compact_chroma_offset =
                        static_cast<size_t>(y / 2) *
                            static_cast<size_t>(width / 2) +
                        static_cast<size_t>(x / 2);
                    (*i420)[y_size + compact_chroma_offset] = u_value;
                    (*i420)[y_size + chroma_size + compact_chroma_offset] =
                        v_value;
                }
            }

            const int c = std::max(0, static_cast<int>(y_value) - 16);
            const int d = static_cast<int>(u_value) - 128;
            const int e = static_cast<int>(v_value) - 128;
            const int red = (298 * c + 409 * e + 128) >> 8;
            const int green = (298 * c - 100 * d - 208 * e + 128) >> 8;
            const int blue = (298 * c + 516 * d + 128) >> 8;
            const size_t rgb_offset =
                (static_cast<size_t>(y) * static_cast<size_t>(width) +
                 static_cast<size_t>(x)) * 3u;
            rgb[rgb_offset] = clamp_byte(red);
            rgb[rgb_offset + 1u] = clamp_byte(green);
            rgb[rgb_offset + 2u] = clamp_byte(blue);
        }
    }
    return true;
}

bool make_ncnn_input(
    const AImage* image,
    int source_width,
    int source_height,
    int input_size,
    const ncnn::Option& option,
    std::vector<uint8_t>& rgb,
    std::vector<uint8_t>* i420,
    ncnn::Mat& input,
    std::string& error
) {
    if (!yuv420_to_rgb(
            image,
            source_width,
            source_height,
            rgb,
            i420,
            error
        )) {
        return false;
    }
    const float scale = std::min(
        static_cast<float>(input_size) / static_cast<float>(source_width),
        static_cast<float>(input_size) / static_cast<float>(source_height)
    );
    const int resized_width = std::max(
        1,
        static_cast<int>(std::lround(source_width * scale))
    );
    const int resized_height = std::max(
        1,
        static_cast<int>(std::lround(source_height * scale))
    );
    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
        rgb.data(),
        ncnn::Mat::PIXEL_RGB,
        source_width,
        source_height,
        resized_width,
        resized_height
    );
    if (resized.empty()) {
        error = "NCNN could not resize the decoded RGB frame.";
        return false;
    }
    const int horizontal_padding = input_size - resized_width;
    const int vertical_padding = input_size - resized_height;
    const int left = horizontal_padding / 2;
    const int right = horizontal_padding - left;
    const int top = vertical_padding / 2;
    const int bottom = vertical_padding - top;
    ncnn::copy_make_border(
        resized,
        input,
        top,
        bottom,
        left,
        right,
        ncnn::BORDER_CONSTANT,
        114.0f,
        option
    );
    if (input.empty()) {
        error = "NCNN could not letterbox the decoded frame.";
        return false;
    }
    const float normalization[3] = {
        1.0f / 255.0f,
        1.0f / 255.0f,
        1.0f / 255.0f
    };
    input.substract_mean_normalize(nullptr, normalization);
    return true;
}

void produce_preprocessed_frames(
    VideoSource& source,
    AMediaCodec* decoder,
    AImageReader* reader,
    ImageSignal& image_signal,
    int input_size,
    int max_frames,
    int frame_stride,
    bool retain_i420,
    const ncnn::Option& preprocessing_option,
    PreparedFrameQueue& queue,
    DecodePreprocessStats& stats
) {
    const auto producer_start = std::chrono::steady_clock::now();
    bool input_eos = false;
    bool output_eos = false;
    int idle_polls = 0;
    int decoded_frame_index = 0;
    std::vector<uint8_t> rgb;
    std::vector<uint8_t> i420;

    while (!output_eos &&
           (max_frames == 0 || stats.frames_prepared < max_frames)) {
        bool made_progress = false;
        if (!input_eos) {
            const ssize_t input_index = AMediaCodec_dequeueInputBuffer(
                decoder,
                0
            );
            if (input_index >= 0) {
                size_t capacity = 0;
                uint8_t* input_buffer = AMediaCodec_getInputBuffer(
                    decoder,
                    static_cast<size_t>(input_index),
                    &capacity
                );
                if (input_buffer == nullptr || capacity == 0) {
                    stats.error =
                        "The pipelined decoder returned an invalid input buffer.";
                    break;
                }
                const ssize_t sample_size = AMediaExtractor_readSampleData(
                    source.extractor.get(),
                    input_buffer,
                    capacity
                );
                if (sample_size < 0) {
                    if (AMediaCodec_queueInputBuffer(
                            decoder,
                            static_cast<size_t>(input_index),
                            0,
                            0,
                            0,
                            AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
                        ) != AMEDIA_OK) {
                        stats.error =
                            "The pipelined decoder could not queue end-of-stream.";
                        break;
                    }
                    input_eos = true;
                } else {
                    int64_t presentation_time_us =
                        AMediaExtractor_getSampleTime(source.extractor.get());
                    if (presentation_time_us < 0) presentation_time_us = 0;
                    if (AMediaCodec_queueInputBuffer(
                            decoder,
                            static_cast<size_t>(input_index),
                            0,
                            static_cast<size_t>(sample_size),
                            presentation_time_us,
                            0
                        ) != AMEDIA_OK) {
                        stats.error =
                            "The pipelined decoder rejected a video sample.";
                        break;
                    }
                    AMediaExtractor_advance(source.extractor.get());
                }
                made_progress = true;
            }
        }

        AMediaCodecBufferInfo output_info{};
        const ssize_t output_index = AMediaCodec_dequeueOutputBuffer(
            decoder,
            &output_info,
            kCodecTimeoutUs
        );
        if (output_index >= 0) {
            const bool render = output_info.size > 0;
            const bool is_eos =
                (output_info.flags &
                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
            if (AMediaCodec_releaseOutputBuffer(
                    decoder,
                    static_cast<size_t>(output_index),
                    render
                ) != AMEDIA_OK) {
                stats.error =
                    "The pipelined decoder could not release a frame.";
                break;
            }
            if (render) {
                ImagePtr image;
                if (!acquire_decoded_image(
                        reader,
                        image_signal,
                        image,
                        stats.error
                    ) ||
                    !validate_yuv_image(
                        image.get(),
                        source.width,
                        source.height,
                        stats.error
                    )) {
                    break;
                }

                const int source_frame_index = decoded_frame_index++;
                if (source_frame_index % frame_stride == 0) {
                    const auto preprocessing_start =
                        std::chrono::steady_clock::now();
                    ncnn::Mat input;
                    if (!make_ncnn_input(
                            image.get(),
                            source.width,
                            source.height,
                            input_size,
                            preprocessing_option,
                            rgb,
                            retain_i420 ? &i420 : nullptr,
                            input,
                            stats.error
                        )) {
                        break;
                    }
                    const auto preprocessing_end =
                        std::chrono::steady_clock::now();
                    stats.preprocessing_time +=
                        preprocessing_end - preprocessing_start;

                    PreparedNativeFrame frame;
                    frame.input = std::move(input);
                    frame.sequence_index = stats.frames_prepared;
                    frame.source_frame_index = source_frame_index;
                    if (retain_i420) {
                        frame.i420 = std::move(i420);
                    }
                    frame.presentation_time_us =
                        output_info.presentationTimeUs;
                    frame.preprocessing_time_ns =
                        std::chrono::duration_cast<std::chrono::nanoseconds>(
                            preprocessing_end - preprocessing_start
                        ).count();
                    if (!queue.push(
                            std::move(frame),
                            stats.queue_wait_time
                        )) {
                        break;
                    }
                    ++stats.frames_prepared;
                }
            }
            output_eos = is_eos;
            made_progress = true;
        } else if (output_index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
                   output_index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            made_progress = true;
        }

        if (made_progress) {
            idle_polls = 0;
        } else if (++idle_polls > kMaxIdlePolls) {
            stats.error =
                "The pipelined decoder stopped making progress.";
            break;
        }
    }

    stats.eos_reached = output_eos;
    const auto producer_time =
        std::chrono::steady_clock::now() - producer_start;
    const auto measured_non_decoder =
        stats.preprocessing_time + stats.queue_wait_time;
    stats.decoder_time = producer_time > measured_non_decoder
        ? producer_time - measured_non_decoder
        : std::chrono::steady_clock::duration::zero();
    queue.finish();
}

struct NativeOutputRows {
    const ncnn::Mat& output;
    int rows = 0;
    int features = 0;
    bool channels_first = false;

    float value(int row_index, int feature_index) const {
        return channels_first
            ? output.row(feature_index)[row_index]
            : output.row(row_index)[feature_index];
    }
};

bool describe_output_rows(
    const ncnn::Mat& output,
    NativeOutputRows& rows,
    std::string& error
) {
    if (output.empty() ||
        output.dims != 2 ||
        output.elembits() != 32 ||
        output.elempack != 1 ||
        output.w <= 0 ||
        output.h <= 0) {
        error = "Native output decoding requires a two-dimensional FP32 tensor.";
        return false;
    }
    const int first = output.h;
    const int second = output.w;
    rows.channels_first = first < 5 && second >= 5
        ? false
        : second < 5 && first >= 5
            ? true
            : first < second && first <= 512;
    rows.rows = rows.channels_first ? second : first;
    rows.features = rows.channels_first ? first : second;
    if (rows.rows <= 0 || rows.features < 5) {
        error = "Native output decoding received an invalid tensor shape.";
        return false;
    }
    return true;
}

bool coordinates_are_normalized(
    float a,
    float b,
    float c,
    float d,
    int coordinate_format
) {
    if (coordinate_format == 2) return true;
    if (coordinate_format == 1) return false;
    return std::max(
        std::max(std::fabs(a), std::fabs(b)),
        std::max(std::fabs(c), std::fabs(d))
    ) <= 1.5f;
}

float model_to_source(
    float value,
    float padding,
    float scale,
    float maximum
) {
    return std::max(
        0.0f,
        std::min(maximum, (value - padding) / scale)
    );
}

float detection_iou(
    const NativeDetection& first,
    const NativeDetection& second
) {
    const float left = std::max(first.left, second.left);
    const float top = std::max(first.top, second.top);
    const float right = std::min(first.right, second.right);
    const float bottom = std::min(first.bottom, second.bottom);
    const float intersection =
        std::max(0.0f, right - left) * std::max(0.0f, bottom - top);
    const float first_area =
        std::max(0.0f, first.right - first.left) *
        std::max(0.0f, first.bottom - first.top);
    const float second_area =
        std::max(0.0f, second.right - second.left) *
        std::max(0.0f, second.bottom - second.top);
    const float union_area = first_area + second_area - intersection;
    return union_area > 0.0f ? intersection / union_area : 0.0f;
}

int infer_raw_class_count(
    int feature_count,
    int configured_class_count,
    bool is_pose
) {
    if (configured_class_count > 0 &&
        (!is_pose ||
         (feature_count - 4 - configured_class_count) % 3 == 0)) {
        return configured_class_count;
    }
    if (!is_pose) return feature_count - 4;
    if (feature_count >= 8 && (feature_count - 5) % 3 == 0) return 1;
    const int largest_candidate = std::min(100, feature_count - 4);
    for (int candidate = 1; candidate <= largest_candidate; ++candidate) {
        const int remaining = feature_count - 4 - candidate;
        if (remaining >= 3 && remaining % 3 == 0) return candidate;
    }
    return 1;
}

bool output_looks_end_to_end(const NativeOutputRows& rows) {
    if (rows.rows > 500 || rows.features < 6) return false;
    const int sample_count = std::min(20, rows.rows);
    int finite_samples = 0;
    int plausible_samples = 0;
    for (int row = 0; row < sample_count; ++row) {
        bool finite = true;
        for (int feature = 0; feature < rows.features; ++feature) {
            if (!std::isfinite(rows.value(row, feature))) {
                finite = false;
                break;
            }
        }
        if (!finite) continue;
        ++finite_samples;
        const float class_value = rows.value(row, 5);
        const float confidence = rows.value(row, 4);
        const bool class_integral =
            std::fabs(class_value - std::round(class_value)) < 0.01f &&
            class_value >= 0.0f;
        const bool confidence_valid =
            confidence >= 0.0f && confidence <= 1.01f;
        const bool corners_valid =
            rows.value(row, 2) >= rows.value(row, 0) &&
            rows.value(row, 3) >= rows.value(row, 1);
        if (class_integral && confidence_valid && corners_valid) {
            ++plausible_samples;
        }
    }
    return finite_samples > 0 &&
        plausible_samples * 2 >= finite_samples;
}

bool map_native_box(
    float left,
    float top,
    float right,
    float bottom,
    float scale,
    float pad_x,
    float pad_y,
    int source_width,
    int source_height,
    NativeDetection& detection
) {
    if (!std::isfinite(left) ||
        !std::isfinite(top) ||
        !std::isfinite(right) ||
        !std::isfinite(bottom)) {
        return false;
    }
    detection.left = model_to_source(
        std::min(left, right),
        pad_x,
        scale,
        static_cast<float>(source_width)
    );
    detection.top = model_to_source(
        std::min(top, bottom),
        pad_y,
        scale,
        static_cast<float>(source_height)
    );
    detection.right = model_to_source(
        std::max(left, right),
        pad_x,
        scale,
        static_cast<float>(source_width)
    );
    detection.bottom = model_to_source(
        std::max(top, bottom),
        pad_y,
        scale,
        static_cast<float>(source_height)
    );
    return detection.right - detection.left >= 1.0f &&
        detection.bottom - detection.top >= 1.0f;
}

NativeKeypoint map_native_keypoint(
    float x,
    float y,
    float confidence,
    int coordinate_format,
    int input_size,
    float scale,
    float pad_x,
    float pad_y,
    int source_width,
    int source_height
) {
    if (coordinates_are_normalized(
            x,
            y,
            x,
            y,
            coordinate_format
        )) {
        x *= static_cast<float>(input_size);
        y *= static_cast<float>(input_size);
    }
    NativeKeypoint point;
    point.x = model_to_source(
        x,
        pad_x,
        scale,
        static_cast<float>(source_width)
    );
    point.y = model_to_source(
        y,
        pad_y,
        scale,
        static_cast<float>(source_height)
    );
    point.confidence = std::isfinite(confidence) ? confidence : 0.0f;
    return point;
}

bool decode_detection_output(
    const ncnn::Mat& output,
    int source_width,
    int source_height,
    int input_size,
    int configured_class_count,
    bool is_pose,
    float confidence_threshold,
    float iou_threshold,
    int detection_count,
    int output_format,
    int coordinate_format,
    std::vector<NativeDetection>& detections,
    std::string& error
) {
    detections.clear();
    NativeOutputRows rows{output, 0, 0, false};
    if (!describe_output_rows(output, rows, error)) return false;
    if (source_width <= 0 || source_height <= 0 || input_size <= 0) {
        error = "Native output decoding received invalid image dimensions.";
        return false;
    }

    const float scale = std::min(
        static_cast<float>(input_size) / static_cast<float>(source_width),
        static_cast<float>(input_size) / static_cast<float>(source_height)
    );
    const float pad_x =
        (static_cast<float>(input_size) - source_width * scale) / 2.0f;
    const float pad_y =
        (static_cast<float>(input_size) - source_height * scale) / 2.0f;
    const bool end_to_end = output_format == 2 ||
        (output_format == 0 && output_looks_end_to_end(rows));
    std::vector<NativeDetection> candidates;
    candidates.reserve(
        static_cast<size_t>(std::min(rows.rows, detection_count * 4))
    );

    if (end_to_end) {
        if (rows.features < 6) {
            error = "The end-to-end output has fewer than six features.";
            return false;
        }
        const int keypoint_count = is_pose
            ? std::max(0, (rows.features - 6) / 3)
            : 0;
        for (int row = 0; row < rows.rows; ++row) {
            const float confidence = rows.value(row, 4);
            const float class_value = rows.value(row, 5);
            if (!std::isfinite(confidence) ||
                confidence < confidence_threshold ||
                !std::isfinite(class_value)) {
                continue;
            }

            float left = rows.value(row, 0);
            float top = rows.value(row, 1);
            float right = rows.value(row, 2);
            float bottom = rows.value(row, 3);
            if (coordinates_are_normalized(
                    left,
                    top,
                    left,
                    top,
                    coordinate_format
                )) {
                left *= static_cast<float>(input_size);
                top *= static_cast<float>(input_size);
            }
            if (coordinates_are_normalized(
                    right,
                    bottom,
                    right,
                    bottom,
                    coordinate_format
                )) {
                right *= static_cast<float>(input_size);
                bottom *= static_cast<float>(input_size);
            }

            NativeDetection detection;
            detection.class_index = std::max(
                0,
                static_cast<int>(std::lround(class_value))
            );
            detection.confidence = confidence;
            if (!map_native_box(
                    left,
                    top,
                    right,
                    bottom,
                    scale,
                    pad_x,
                    pad_y,
                    source_width,
                    source_height,
                    detection
                )) {
                continue;
            }
            detection.keypoints.reserve(
                static_cast<size_t>(keypoint_count)
            );
            for (int keypoint = 0; keypoint < keypoint_count; ++keypoint) {
                const int offset = 6 + keypoint * 3;
                detection.keypoints.push_back(map_native_keypoint(
                    rows.value(row, offset),
                    rows.value(row, offset + 1),
                    rows.value(row, offset + 2),
                    coordinate_format,
                    input_size,
                    scale,
                    pad_x,
                    pad_y,
                    source_width,
                    source_height
                ));
            }
            candidates.push_back(std::move(detection));
        }
    } else {
        const int class_count = infer_raw_class_count(
            rows.features,
            configured_class_count,
            is_pose
        );
        const int keypoint_start = 4 + class_count;
        if (class_count <= 0 || rows.features < keypoint_start) {
            error = "The raw detection/pose output does not contain valid class features.";
            return false;
        }
        const int keypoint_count = is_pose
            ? std::max(0, (rows.features - keypoint_start) / 3)
            : 0;
        for (int row = 0; row < rows.rows; ++row) {
            int class_index = 0;
            float confidence = rows.value(row, 4);
            for (int class_offset = 1;
                 class_offset < class_count;
                 ++class_offset) {
                const float score = rows.value(row, 4 + class_offset);
                if (score > confidence) {
                    confidence = score;
                    class_index = class_offset;
                }
            }
            if (!std::isfinite(confidence) ||
                confidence < confidence_threshold) {
                continue;
            }

            float center_x = rows.value(row, 0);
            float center_y = rows.value(row, 1);
            float width = rows.value(row, 2);
            float height = rows.value(row, 3);
            if (coordinates_are_normalized(
                    center_x,
                    center_y,
                    width,
                    height,
                    coordinate_format
                )) {
                center_x *= static_cast<float>(input_size);
                center_y *= static_cast<float>(input_size);
                width *= static_cast<float>(input_size);
                height *= static_cast<float>(input_size);
            }

            NativeDetection detection;
            detection.class_index = class_index;
            detection.confidence = confidence;
            if (!map_native_box(
                    center_x - width / 2.0f,
                    center_y - height / 2.0f,
                    center_x + width / 2.0f,
                    center_y + height / 2.0f,
                    scale,
                    pad_x,
                    pad_y,
                    source_width,
                    source_height,
                    detection
                )) {
                continue;
            }
            detection.keypoints.reserve(
                static_cast<size_t>(keypoint_count)
            );
            for (int keypoint = 0; keypoint < keypoint_count; ++keypoint) {
                const int offset = keypoint_start + keypoint * 3;
                detection.keypoints.push_back(map_native_keypoint(
                    rows.value(row, offset),
                    rows.value(row, offset + 1),
                    rows.value(row, offset + 2),
                    coordinate_format,
                    input_size,
                    scale,
                    pad_x,
                    pad_y,
                    source_width,
                    source_height
                ));
            }
            candidates.push_back(std::move(detection));
        }
    }

    std::stable_sort(
        candidates.begin(),
        candidates.end(),
        [](const NativeDetection& first, const NativeDetection& second) {
            return first.confidence > second.confidence;
        }
    );
    if (end_to_end) {
        const int retained_count = std::min(
            detection_count,
            static_cast<int>(candidates.size())
        );
        detections.assign(
            candidates.begin(),
            candidates.begin() + retained_count
        );
        return true;
    }
    detections.reserve(
        static_cast<size_t>(std::min(
            detection_count,
            static_cast<int>(candidates.size())
        ))
    );
    for (const NativeDetection& candidate : candidates) {
        bool suppressed = false;
        for (const NativeDetection& kept : detections) {
            if (kept.class_index == candidate.class_index &&
                detection_iou(kept, candidate) > iou_threshold) {
                suppressed = true;
                break;
            }
        }
        if (!suppressed) {
            detections.push_back(candidate);
            if (static_cast<int>(detections.size()) >= detection_count) break;
        }
    }
    return true;
}

bool dispatch_frame_callback(
    JNIEnv* env,
    jobject callback,
    jmethodID callback_method,
    int frame_index,
    const PreparedNativeFrame& frame,
    int source_width,
    int source_height,
    int64_t inference_time_ns,
    int64_t postprocessing_time_ns,
    std::vector<NativeDetection>& detections,
    std::string& error
) {
    if (callback == nullptr || callback_method == nullptr) return true;
    const jsize detection_count =
        static_cast<jsize>(detections.size());
    size_t keypoint_count = 0;
    for (const NativeDetection& detection : detections) {
        keypoint_count += detection.keypoints.size();
    }
    if (keypoint_count > static_cast<size_t>(INT32_MAX / 3)) {
        error = "The native frame contains too many keypoints for JNI.";
        return false;
    }

    std::vector<jint> class_ids(static_cast<size_t>(detection_count));
    std::vector<jfloat> confidences(static_cast<size_t>(detection_count));
    std::vector<jfloat> boxes(
        static_cast<size_t>(detection_count) * 4u
    );
    std::vector<jint> keypoint_offsets(
        static_cast<size_t>(detection_count) + 1u
    );
    std::vector<jfloat> keypoints(keypoint_count * 3u);
    size_t keypoint_index = 0;
    for (jsize index = 0; index < detection_count; ++index) {
        const NativeDetection& detection =
            detections[static_cast<size_t>(index)];
        class_ids[static_cast<size_t>(index)] = detection.class_index;
        confidences[static_cast<size_t>(index)] = detection.confidence;
        const size_t box_offset = static_cast<size_t>(index) * 4u;
        boxes[box_offset] = detection.left;
        boxes[box_offset + 1u] = detection.top;
        boxes[box_offset + 2u] = detection.right;
        boxes[box_offset + 3u] = detection.bottom;
        keypoint_offsets[static_cast<size_t>(index)] =
            static_cast<jint>(keypoint_index);
        for (const NativeKeypoint& point : detection.keypoints) {
            const size_t point_offset = keypoint_index * 3u;
            keypoints[point_offset] = point.x;
            keypoints[point_offset + 1u] = point.y;
            keypoints[point_offset + 2u] = point.confidence;
            ++keypoint_index;
        }
    }
    keypoint_offsets[static_cast<size_t>(detection_count)] =
        static_cast<jint>(keypoint_index);

    jintArray class_array = env->NewIntArray(detection_count);
    jfloatArray confidence_array = env->NewFloatArray(detection_count);
    jfloatArray box_array = env->NewFloatArray(detection_count * 4);
    jintArray offset_array = env->NewIntArray(detection_count + 1);
    jfloatArray keypoint_array = env->NewFloatArray(
        static_cast<jsize>(keypoints.size())
    );
    if (class_array == nullptr ||
        confidence_array == nullptr ||
        box_array == nullptr ||
        offset_array == nullptr ||
        keypoint_array == nullptr) {
        error = "The native pipeline could not allocate callback arrays.";
        return false;
    }
    if (detection_count > 0) {
        env->SetIntArrayRegion(
            class_array,
            0,
            detection_count,
            class_ids.data()
        );
        env->SetFloatArrayRegion(
            confidence_array,
            0,
            detection_count,
            confidences.data()
        );
        env->SetFloatArrayRegion(
            box_array,
            0,
            detection_count * 4,
            boxes.data()
        );
    }
    env->SetIntArrayRegion(
        offset_array,
        0,
        detection_count + 1,
        keypoint_offsets.data()
    );
    if (!keypoints.empty()) {
        env->SetFloatArrayRegion(
            keypoint_array,
            0,
            static_cast<jsize>(keypoints.size()),
            keypoints.data()
        );
    }
    if (env->ExceptionCheck()) {
        error = "The native frame callback input arrays could not be populated.";
        return false;
    }

    jintArray track_array = reinterpret_cast<jintArray>(
        env->CallObjectMethod(
            callback,
            callback_method,
            static_cast<jint>(frame_index),
            static_cast<jlong>(frame.presentation_time_us),
            static_cast<jint>(source_width),
            static_cast<jint>(source_height),
            static_cast<jlong>(inference_time_ns),
            static_cast<jlong>(frame.preprocessing_time_ns),
            static_cast<jlong>(postprocessing_time_ns),
            class_array,
            confidence_array,
            box_array,
            offset_array,
            keypoint_array
        )
    );
    env->DeleteLocalRef(class_array);
    env->DeleteLocalRef(confidence_array);
    env->DeleteLocalRef(box_array);
    env->DeleteLocalRef(offset_array);
    env->DeleteLocalRef(keypoint_array);
    if (env->ExceptionCheck()) {
        error = "The Kotlin native-frame callback failed.";
        return false;
    }
    if (track_array == nullptr ||
        env->GetArrayLength(track_array) != detection_count) {
        if (track_array != nullptr) env->DeleteLocalRef(track_array);
        error =
            "The native frame callback returned an invalid track ID array.";
        return false;
    }
    if (detection_count > 0) {
        std::vector<jint> track_ids(
            static_cast<size_t>(detection_count)
        );
        env->GetIntArrayRegion(
            track_array,
            0,
            detection_count,
            track_ids.data()
        );
        if (env->ExceptionCheck()) {
            error = "The native frame callback track IDs could not be read.";
            env->DeleteLocalRef(track_array);
            return false;
        }
        for (jsize index = 0; index < detection_count; ++index) {
            detections[static_cast<size_t>(index)].track_id =
                track_ids[static_cast<size_t>(index)];
        }
    }
    env->DeleteLocalRef(track_array);
    return true;
}

jobject make_decode_benchmark(
    JNIEnv* env,
    const VideoSource& source,
    int frames_decoded,
    int frames_requested,
    int64_t wall_time_ms,
    double decode_fps,
    const std::string& decoder_name,
    bool eos_reached
) {
    jclass result_class = env->FindClass(
        "com/integrapose/mobile/offline/NativeDecodeBenchmark"
    );
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class,
        "<init>",
        "(IIIIIJJDLjava/lang/String;Ljava/lang/String;Z)V"
    );
    if (constructor == nullptr) return nullptr;
    jstring mime = env->NewStringUTF(source.mime.c_str());
    if (mime == nullptr) return nullptr;
    jstring decoder = env->NewStringUTF(decoder_name.c_str());
    if (decoder == nullptr) return nullptr;
    return env->NewObject(
        result_class,
        constructor,
        static_cast<jint>(frames_decoded),
        static_cast<jint>(frames_requested),
        static_cast<jint>(source.width),
        static_cast<jint>(source.height),
        static_cast<jint>(source.rotation_degrees),
        static_cast<jlong>(source.duration_us),
        static_cast<jlong>(wall_time_ms),
        static_cast<jdouble>(decode_fps),
        mime,
        decoder,
        static_cast<jboolean>(eos_reached)
    );
}

jobject make_ncnn_pipeline_benchmark(
    JNIEnv* env,
    const VideoSource& source,
    int frames_processed,
    int frames_requested,
    int threads,
    int workers,
    int input_size,
    int total_detections,
    int frames_with_detections,
    int frames_encoded,
    int64_t wall_time_ms,
    int64_t decoder_time_ms,
    int64_t preprocessing_time_ms,
    int64_t inference_time_ms,
    int64_t output_time_ms,
    int64_t postprocessing_time_ms,
    int64_t annotation_time_ms,
    int64_t encoding_time_ms,
    double pipeline_fps,
    double inference_fps,
    bool use_vulkan,
    bool callback_used,
    const std::string& decoder_name,
    bool eos_reached
) {
    jclass result_class = env->FindClass(
        "com/integrapose/mobile/offline/NativeNcnnPipelineBenchmark"
    );
    if (result_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(
        result_class,
        "<init>",
        "(IIIIIIIIIIJJJJJJJJDDZLjava/lang/String;Z)V"
    );
    if (constructor == nullptr) return nullptr;
    std::string backend;
    if (use_vulkan) {
        backend = "NCNN Vulkan native pipelined";
    } else {
        const char* worker_unit = workers == 1 ? " worker x " : " workers x ";
        const char* thread_unit = threads == 1 ? " thread)" : " threads)";
        backend =
            "NCNN CPU native pipelined (" +
            std::to_string(workers) +
            worker_unit +
            std::to_string(threads) +
            thread_unit;
    }
    if (!decoder_name.empty()) {
        backend += " + compatibility decoder (" + decoder_name + ")";
    }
    if (callback_used) backend += " + tracking/CSV callback";
    if (frames_encoded > 0) backend += " + native H.264";
    jstring backend_value = env->NewStringUTF(backend.c_str());
    if (backend_value == nullptr) return nullptr;
    return env->NewObject(
        result_class,
        constructor,
        static_cast<jint>(frames_processed),
        static_cast<jint>(frames_requested),
        static_cast<jint>(threads),
        static_cast<jint>(workers),
        static_cast<jint>(source.width),
        static_cast<jint>(source.height),
        static_cast<jint>(input_size),
        static_cast<jint>(total_detections),
        static_cast<jint>(frames_with_detections),
        static_cast<jint>(frames_encoded),
        static_cast<jlong>(wall_time_ms),
        static_cast<jlong>(decoder_time_ms),
        static_cast<jlong>(preprocessing_time_ms),
        static_cast<jlong>(inference_time_ms),
        static_cast<jlong>(output_time_ms),
        static_cast<jlong>(postprocessing_time_ms),
        static_cast<jlong>(annotation_time_ms),
        static_cast<jlong>(encoding_time_ms),
        static_cast<jdouble>(pipeline_fps),
        static_cast<jdouble>(inference_fps),
        static_cast<jboolean>(use_vulkan),
        backend_value,
        static_cast<jboolean>(eos_reached)
    );
}

}  // namespace
