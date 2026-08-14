#pragma once

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace integrapose::media {

struct OverlayKeypoint {
    float x = 0.0f;
    float y = 0.0f;
    float confidence = 0.0f;
};

struct OverlayDetection {
    int class_index = 0;
    int track_id = -1;
    float confidence = 0.0f;
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    std::vector<OverlayKeypoint> keypoints;
};

struct OverlayRoi {
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    std::string name;
    uint8_t red = 255;
    uint8_t green = 205;
    uint8_t blue = 64;
    uint8_t alpha = 235;
};

struct AnnotationColors {
    uint8_t box_red = 86;
    uint8_t box_green = 214;
    uint8_t box_blue = 165;
    uint8_t box_alpha = 230;
    uint8_t keypoint_red = 255;
    uint8_t keypoint_green = 138;
    uint8_t keypoint_blue = 74;
    uint8_t keypoint_alpha = 235;
};

using SkeletonEdge = std::array<int, 2>;

struct AnnotatedI420Frame {
    std::vector<uint8_t> i420;
    int64_t presentation_time_us = 0;
};

class AnnotatedFrameQueue {
public:
    explicit AnnotatedFrameQueue(size_t capacity);

    bool push(
        AnnotatedI420Frame&& frame,
        std::chrono::nanoseconds& wait_time
    );
    bool pop(AnnotatedI420Frame& frame);
    void finish();
    void cancel();

private:
    const size_t capacity_;
    std::mutex mutex_;
    std::condition_variable not_empty_;
    std::condition_variable not_full_;
    std::deque<AnnotatedI420Frame> frames_;
    bool finished_ = false;
    bool cancelled_ = false;
};

struct EncoderStats {
    int frames_encoded = 0;
    bool completed = false;
    std::string error;
    std::chrono::nanoseconds color_conversion_time{0};
    std::chrono::nanoseconds codec_time{0};
    std::chrono::nanoseconds queue_wait_time{0};
};

bool draw_annotations_rgb(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    const std::vector<OverlayDetection>& detections,
    const std::vector<SkeletonEdge>& skeleton_edges,
    std::string& error
);

bool draw_annotations_i420(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    const std::vector<OverlayDetection>& detections,
    const std::vector<OverlayRoi>& rois,
    const AnnotationColors& colors,
    const std::vector<SkeletonEdge>& skeleton_edges,
    int roi_label_size,
    std::string& error
);

void encode_annotated_frames(
    const std::string& output_path,
    int width,
    int height,
    int rotation_degrees,
    double frame_rate,
    AnnotatedFrameQueue& queue,
    EncoderStats& stats
);

}  // namespace integrapose::media
