#include "native_annotated_encoder.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>

namespace integrapose::media {
namespace {

constexpr int64_t kCodecTimeoutUs = 10'000;
constexpr int kMaxInputPolls = 100;
constexpr int kMaxEndOfStreamPolls = 500;
constexpr int32_t kColorFormatYuv420Planar = 0x13;
constexpr int32_t kColorFormatYuv420SemiPlanar = 0x15;
constexpr int32_t kColorFormatYuv420Flexible = 0x7F420888;
constexpr float kKeypointThreshold = 0.25f;

enum class EncoderInputLayout {
    I420,
    NV12
};

struct EncoderInputCandidate {
    int32_t color_format;
    EncoderInputLayout layout;
};

uint8_t clamp_byte(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}

void blend_pixel(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    int x,
    int y,
    uint8_t red,
    uint8_t green,
    uint8_t blue,
    uint8_t alpha
) {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    const size_t offset =
        (static_cast<size_t>(y) * static_cast<size_t>(width) +
         static_cast<size_t>(x)) * 3u;
    const int inverse_alpha = 255 - static_cast<int>(alpha);
    rgb[offset] = static_cast<uint8_t>(
        (static_cast<int>(red) * alpha + rgb[offset] * inverse_alpha) / 255
    );
    rgb[offset + 1u] = static_cast<uint8_t>(
        (static_cast<int>(green) * alpha +
         rgb[offset + 1u] * inverse_alpha) / 255
    );
    rgb[offset + 2u] = static_cast<uint8_t>(
        (static_cast<int>(blue) * alpha +
         rgb[offset + 2u] * inverse_alpha) / 255
    );
}

void draw_disc(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    int center_x,
    int center_y,
    int radius,
    uint8_t red,
    uint8_t green,
    uint8_t blue,
    uint8_t alpha
) {
    const int radius_squared = radius * radius;
    for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
            if (x * x + y * y <= radius_squared) {
                blend_pixel(
                    rgb,
                    width,
                    height,
                    center_x + x,
                    center_y + y,
                    red,
                    green,
                    blue,
                    alpha
                );
            }
        }
    }
}

void draw_line(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    int first_x,
    int first_y,
    int second_x,
    int second_y,
    int thickness,
    uint8_t red,
    uint8_t green,
    uint8_t blue,
    uint8_t alpha
) {
    int x = first_x;
    int y = first_y;
    const int delta_x = std::abs(second_x - first_x);
    const int step_x = first_x < second_x ? 1 : -1;
    const int delta_y = -std::abs(second_y - first_y);
    const int step_y = first_y < second_y ? 1 : -1;
    int line_error = delta_x + delta_y;
    const int radius = std::max(0, thickness / 2);
    while (true) {
        draw_disc(
            rgb,
            width,
            height,
            x,
            y,
            radius,
            red,
            green,
            blue,
            alpha
        );
        if (x == second_x && y == second_y) break;
        const int doubled_error = line_error * 2;
        if (doubled_error >= delta_y) {
            line_error += delta_y;
            x += step_x;
        }
        if (doubled_error <= delta_x) {
            line_error += delta_x;
            y += step_y;
        }
    }
}

void fill_rectangle(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    int left,
    int top,
    int right,
    int bottom,
    uint8_t red,
    uint8_t green,
    uint8_t blue,
    uint8_t alpha
) {
    left = std::max(0, std::min(width, left));
    right = std::max(0, std::min(width, right));
    top = std::max(0, std::min(height, top));
    bottom = std::max(0, std::min(height, bottom));
    for (int y = top; y < bottom; ++y) {
        for (int x = left; x < right; ++x) {
            blend_pixel(
                rgb,
                width,
                height,
                x,
                y,
                red,
                green,
                blue,
                alpha
            );
        }
    }
}

std::array<uint8_t, 7> glyph_rows(char value) {
    const char glyph = static_cast<char>(std::toupper(
        static_cast<unsigned char>(value)
    ));
    switch (glyph) {
        case '0': return {14, 17, 19, 21, 25, 17, 14};
        case '1': return {4, 12, 4, 4, 4, 4, 14};
        case '2': return {14, 17, 1, 2, 4, 8, 31};
        case '3': return {30, 1, 1, 14, 1, 1, 30};
        case '4': return {2, 6, 10, 18, 31, 2, 2};
        case '5': return {31, 16, 16, 30, 1, 1, 30};
        case '6': return {14, 16, 16, 30, 17, 17, 14};
        case '7': return {31, 1, 2, 4, 8, 8, 8};
        case '8': return {14, 17, 17, 14, 17, 17, 14};
        case '9': return {14, 17, 17, 15, 1, 1, 14};
        case 'A': return {14, 17, 17, 31, 17, 17, 17};
        case 'B': return {30, 17, 17, 30, 17, 17, 30};
        case 'C': return {15, 16, 16, 16, 16, 16, 15};
        case 'D': return {30, 17, 17, 17, 17, 17, 30};
        case 'E': return {31, 16, 16, 30, 16, 16, 31};
        case 'F': return {31, 16, 16, 30, 16, 16, 16};
        case 'G': return {14, 17, 16, 23, 17, 17, 15};
        case 'H': return {17, 17, 17, 31, 17, 17, 17};
        case 'I': return {14, 4, 4, 4, 4, 4, 14};
        case 'J': return {7, 2, 2, 2, 18, 18, 12};
        case 'K': return {17, 18, 20, 24, 20, 18, 17};
        case 'L': return {16, 16, 16, 16, 16, 16, 31};
        case 'M': return {17, 27, 21, 21, 17, 17, 17};
        case 'N': return {17, 25, 21, 19, 17, 17, 17};
        case 'O': return {14, 17, 17, 17, 17, 17, 14};
        case 'P': return {30, 17, 17, 30, 16, 16, 16};
        case 'Q': return {14, 17, 17, 17, 21, 18, 13};
        case 'R': return {30, 17, 17, 30, 20, 18, 17};
        case 'S': return {15, 16, 16, 14, 1, 1, 30};
        case 'T': return {31, 4, 4, 4, 4, 4, 4};
        case 'U': return {17, 17, 17, 17, 17, 17, 14};
        case 'V': return {17, 17, 17, 17, 17, 10, 4};
        case 'W': return {17, 17, 17, 21, 21, 21, 10};
        case 'X': return {17, 17, 10, 4, 10, 17, 17};
        case 'Y': return {17, 17, 10, 4, 4, 4, 4};
        case 'Z': return {31, 1, 2, 4, 8, 16, 31};
        case '#': return {10, 10, 31, 10, 31, 10, 10};
        case ':': return {0, 6, 6, 0, 6, 6, 0};
        case '.': return {0, 0, 0, 0, 0, 6, 6};
        case '-': return {0, 0, 0, 31, 0, 0, 0};
        case '_': return {0, 0, 0, 0, 0, 0, 31};
        default: return {0, 0, 0, 0, 0, 0, 0};
    }
}

void draw_text(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    int left,
    int top,
    const std::string& text,
    int scale
) {
    int cursor_x = left;
    for (const char value : text) {
        const std::array<uint8_t, 7> rows = glyph_rows(value);
        for (int row = 0; row < 7; ++row) {
            for (int column = 0; column < 5; ++column) {
                if ((rows[static_cast<size_t>(row)] & (1u << (4 - column))) == 0) {
                    continue;
                }
                fill_rectangle(
                    rgb,
                    width,
                    height,
                    cursor_x + column * scale,
                    top + row * scale,
                    cursor_x + (column + 1) * scale,
                    top + (row + 1) * scale,
                    255,
                    255,
                    255,
                    255
                );
            }
        }
        cursor_x += 6 * scale;
    }
}

bool rgb_to_i420(
    const std::vector<uint8_t>& rgb,
    int width,
    int height,
    std::vector<uint8_t>& i420,
    std::string& error
) {
    const size_t pixel_count =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 ||
        rgb.size() != pixel_count * 3u) {
        error = "The native encoder received an invalid RGB frame.";
        return false;
    }
    const size_t chroma_count = pixel_count / 4u;
    i420.resize(pixel_count + chroma_count * 2u);
    size_t y_index = 0;
    size_t u_index = pixel_count;
    size_t v_index = pixel_count + chroma_count;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t offset =
                (static_cast<size_t>(y) * static_cast<size_t>(width) +
                 static_cast<size_t>(x)) * 3u;
            const int red = rgb[offset];
            const int green = rgb[offset + 1u];
            const int blue = rgb[offset + 2u];
            i420[y_index++] = clamp_byte(
                ((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16
            );
            if (y % 2 == 0 && x % 2 == 0) {
                i420[u_index++] = clamp_byte(
                    ((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128
                );
                i420[v_index++] = clamp_byte(
                    ((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128
                );
            }
        }
    }
    return true;
}

bool i420_to_nv12(
    const std::vector<uint8_t>& i420,
    int width,
    int height,
    std::vector<uint8_t>& nv12,
    std::string& error
) {
    const size_t luma_size =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t chroma_size = luma_size / 4u;
    const size_t expected_size = luma_size + chroma_size * 2u;
    if (width <= 0 || height <= 0 || width % 2 != 0 ||
        height % 2 != 0 || i420.size() != expected_size) {
        error = "The native encoder could not convert an invalid I420 frame.";
        return false;
    }
    nv12.resize(expected_size);
    std::copy_n(i420.data(), luma_size, nv12.data());
    const uint8_t* u_plane = i420.data() + luma_size;
    const uint8_t* v_plane = u_plane + chroma_size;
    uint8_t* interleaved = nv12.data() + luma_size;
    for (size_t index = 0; index < chroma_size; ++index) {
        interleaved[index * 2u] = u_plane[index];
        interleaved[index * 2u + 1u] = v_plane[index];
    }
    return true;
}

struct ScopedFd {
    int value = -1;
    ~ScopedFd() {
        if (value >= 0) close(value);
    }
};

struct FormatDeleter {
    void operator()(AMediaFormat* value) const {
        if (value != nullptr) AMediaFormat_delete(value);
    }
};

using FormatPtr = std::unique_ptr<AMediaFormat, FormatDeleter>;

struct CodecGuard {
    AMediaCodec* value = nullptr;
    bool started = false;
    ~CodecGuard() {
        if (value == nullptr) return;
        if (started) AMediaCodec_stop(value);
        AMediaCodec_delete(value);
    }
};

struct MuxerGuard {
    AMediaMuxer* value = nullptr;
    bool started = false;
    ~MuxerGuard() {
        if (value == nullptr) return;
        if (started) AMediaMuxer_stop(value);
        AMediaMuxer_delete(value);
    }
};

}  // namespace

AnnotatedFrameQueue::AnnotatedFrameQueue(size_t capacity)
    : capacity_(std::max<size_t>(1, capacity)) {}

bool AnnotatedFrameQueue::push(
    AnnotatedI420Frame&& frame,
    std::chrono::nanoseconds& wait_time
) {
    const auto wait_start = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lock(mutex_);
    not_full_.wait(lock, [this]() {
        return cancelled_ || frames_.size() < capacity_;
    });
    wait_time += std::chrono::steady_clock::now() - wait_start;
    if (cancelled_ || finished_) return false;
    frames_.push_back(std::move(frame));
    not_empty_.notify_one();
    return true;
}

bool AnnotatedFrameQueue::pop(AnnotatedI420Frame& frame) {
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

void AnnotatedFrameQueue::finish() {
    std::lock_guard<std::mutex> lock(mutex_);
    finished_ = true;
    not_empty_.notify_all();
    not_full_.notify_all();
}

void AnnotatedFrameQueue::cancel() {
    std::lock_guard<std::mutex> lock(mutex_);
    cancelled_ = true;
    not_empty_.notify_all();
    not_full_.notify_all();
}

bool draw_annotations_rgb(
    std::vector<uint8_t>& rgb,
    int width,
    int height,
    const std::vector<OverlayDetection>& detections,
    const std::vector<SkeletonEdge>& skeleton_edges,
    std::string& error
) {
    const size_t expected_size =
        static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    if (width <= 0 || height <= 0 || rgb.size() != expected_size) {
        error = "The native overlay received an invalid RGB frame.";
        return false;
    }
    const int minimum_dimension = std::min(width, height);
    const int box_thickness = std::max(2, minimum_dimension / 180);
    const int skeleton_thickness = std::max(2, minimum_dimension / 240);
    const int point_radius = std::max(3, minimum_dimension / 120);
    const int font_scale = std::max(1, std::min(3, minimum_dimension / 240));
    for (const OverlayDetection& detection : detections) {
        if (!std::isfinite(detection.left) ||
            !std::isfinite(detection.top) ||
            !std::isfinite(detection.right) ||
            !std::isfinite(detection.bottom)) {
            continue;
        }
        const int left = static_cast<int>(std::lround(std::min(
            detection.left,
            detection.right
        )));
        const int right = static_cast<int>(std::lround(std::max(
            detection.left,
            detection.right
        )));
        const int top = static_cast<int>(std::lround(std::min(
            detection.top,
            detection.bottom
        )));
        const int bottom = static_cast<int>(std::lround(std::max(
            detection.top,
            detection.bottom
        )));
        draw_line(rgb, width, height, left, top, right, top, box_thickness,
                  86, 214, 165, 230);
        draw_line(rgb, width, height, right, top, right, bottom, box_thickness,
                  86, 214, 165, 230);
        draw_line(rgb, width, height, right, bottom, left, bottom, box_thickness,
                  86, 214, 165, 230);
        draw_line(rgb, width, height, left, bottom, left, top, box_thickness,
                  86, 214, 165, 230);

        for (const SkeletonEdge& edge : skeleton_edges) {
            if (edge[0] < 0 || edge[1] < 0 ||
                static_cast<size_t>(edge[0]) >= detection.keypoints.size() ||
                static_cast<size_t>(edge[1]) >= detection.keypoints.size()) {
                continue;
            }
            const OverlayKeypoint& first =
                detection.keypoints[static_cast<size_t>(edge[0])];
            const OverlayKeypoint& second =
                detection.keypoints[static_cast<size_t>(edge[1])];
            if (first.confidence < kKeypointThreshold ||
                second.confidence < kKeypointThreshold) {
                continue;
            }
            draw_line(
                rgb,
                width,
                height,
                static_cast<int>(std::lround(first.x)),
                static_cast<int>(std::lround(first.y)),
                static_cast<int>(std::lround(second.x)),
                static_cast<int>(std::lround(second.y)),
                skeleton_thickness,
                75,
                195,
                255,
                210
            );
        }
        for (const OverlayKeypoint& point : detection.keypoints) {
            if (point.confidence < kKeypointThreshold ||
                !std::isfinite(point.x) ||
                !std::isfinite(point.y)) {
                continue;
            }
            draw_disc(
                rgb,
                width,
                height,
                static_cast<int>(std::lround(point.x)),
                static_cast<int>(std::lround(point.y)),
                point_radius,
                255,
                138,
                74,
                235
            );
        }

        char label_buffer[96]{};
        if (detection.track_id >= 0) {
            std::snprintf(
                label_buffer,
                sizeof(label_buffer),
                "T%d C%d %.2f",
                detection.track_id,
                detection.class_index,
                detection.confidence
            );
        } else {
            std::snprintf(
                label_buffer,
                sizeof(label_buffer),
                "C%d %.2f",
                detection.class_index,
                detection.confidence
            );
        }
        const std::string label(label_buffer);
        const int label_width =
            static_cast<int>(label.size()) * 6 * font_scale + 6;
        const int label_height = 7 * font_scale + 6;
        const int label_left = std::max(0, std::min(width - 1, left));
        const int label_top = top >= label_height + 2
            ? top - label_height - 2
            : std::max(0, std::min(height - label_height, top + 2));
        fill_rectangle(
            rgb,
            width,
            height,
            label_left,
            label_top,
            std::min(width, label_left + label_width),
            std::min(height, label_top + label_height),
            0,
            0,
            0,
            210
        );
        draw_text(
            rgb,
            width,
            height,
            label_left + 3,
            label_top + 3,
            label,
            font_scale
        );
    }
    return true;
}

namespace {

struct I420Color {
    uint8_t y = 16;
    uint8_t u = 128;
    uint8_t v = 128;
    uint8_t alpha = 255;
};

I420Color make_i420_color(
    int red,
    int green,
    int blue,
    uint8_t alpha
) {
    return I420Color{
        clamp_byte(
            ((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16
        ),
        clamp_byte(
            ((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128
        ),
        clamp_byte(
            ((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128
        ),
        alpha
    };
}

uint8_t blend_channel(uint8_t source, uint8_t overlay, uint8_t alpha) {
    const int inverse_alpha = 255 - static_cast<int>(alpha);
    return static_cast<uint8_t>(
        (static_cast<int>(overlay) * alpha + source * inverse_alpha) / 255
    );
}

void blend_i420_pixel(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    int x,
    int y,
    const I420Color& color
) {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    const size_t y_size =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t chroma_width = static_cast<size_t>(width / 2);
    const size_t chroma_size = y_size / 4u;
    const size_t y_offset =
        static_cast<size_t>(y) * static_cast<size_t>(width) +
        static_cast<size_t>(x);
    const size_t chroma_offset =
        static_cast<size_t>(y / 2) * chroma_width +
        static_cast<size_t>(x / 2);
    const size_t u_offset = y_size + chroma_offset;
    const size_t v_offset = y_size + chroma_size + chroma_offset;
    i420[y_offset] = blend_channel(i420[y_offset], color.y, color.alpha);
    i420[u_offset] = blend_channel(i420[u_offset], color.u, color.alpha);
    i420[v_offset] = blend_channel(i420[v_offset], color.v, color.alpha);
}

void draw_i420_disc(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    int center_x,
    int center_y,
    int radius,
    const I420Color& color
) {
    const int radius_squared = radius * radius;
    for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
            if (x * x + y * y <= radius_squared) {
                blend_i420_pixel(
                    i420,
                    width,
                    height,
                    center_x + x,
                    center_y + y,
                    color
                );
            }
        }
    }
}

void draw_i420_line(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    int first_x,
    int first_y,
    int second_x,
    int second_y,
    int thickness,
    const I420Color& color
) {
    int x = first_x;
    int y = first_y;
    const int delta_x = std::abs(second_x - first_x);
    const int step_x = first_x < second_x ? 1 : -1;
    const int delta_y = -std::abs(second_y - first_y);
    const int step_y = first_y < second_y ? 1 : -1;
    int line_error = delta_x + delta_y;
    const int radius = std::max(0, thickness / 2);
    while (true) {
        draw_i420_disc(
            i420,
            width,
            height,
            x,
            y,
            radius,
            color
        );
        if (x == second_x && y == second_y) break;
        const int doubled_error = line_error * 2;
        if (doubled_error >= delta_y) {
            line_error += delta_y;
            x += step_x;
        }
        if (doubled_error <= delta_x) {
            line_error += delta_x;
            y += step_y;
        }
    }
}

void fill_i420_rectangle(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    int left,
    int top,
    int right,
    int bottom,
    const I420Color& color
) {
    left = std::max(0, std::min(width, left));
    right = std::max(0, std::min(width, right));
    top = std::max(0, std::min(height, top));
    bottom = std::max(0, std::min(height, bottom));
    for (int y = top; y < bottom; ++y) {
        for (int x = left; x < right; ++x) {
            blend_i420_pixel(i420, width, height, x, y, color);
        }
    }
}

void draw_i420_text(
    std::vector<uint8_t>& i420,
    int width,
    int height,
    int left,
    int top,
    const std::string& text,
    int scale,
    const I420Color& color
) {
    int cursor_x = left;
    for (const char value : text) {
        const std::array<uint8_t, 7> rows = glyph_rows(value);
        for (int row = 0; row < 7; ++row) {
            for (int column = 0; column < 5; ++column) {
                if ((rows[static_cast<size_t>(row)] &
                     (1u << (4 - column))) == 0) {
                    continue;
                }
                fill_i420_rectangle(
                    i420,
                    width,
                    height,
                    cursor_x + column * scale,
                    top + row * scale,
                    cursor_x + (column + 1) * scale,
                    top + (row + 1) * scale,
                    color
                );
            }
        }
        cursor_x += 6 * scale;
    }
}

}  // namespace

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
) {
    const size_t expected_size =
        static_cast<size_t>(width) * static_cast<size_t>(height) * 3u / 2u;
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 ||
        i420.size() != expected_size) {
        error = "The native I420 overlay received an invalid frame.";
        return false;
    }
    const int minimum_dimension = std::min(width, height);
    const int box_thickness = std::max(2, minimum_dimension / 180);
    const int roi_thickness = std::max(2, minimum_dimension / 180);
    const int skeleton_thickness = std::max(2, minimum_dimension / 240);
    const int point_radius = std::max(3, minimum_dimension / 120);
    const int font_scale = std::max(1, std::min(3, minimum_dimension / 240));
    const int roi_font_scale = roi_label_size <= 0
        ? 0
        : std::max(1, std::min(6, font_scale + roi_label_size - 1));
    const I420Color box_color = make_i420_color(
        colors.box_red,
        colors.box_green,
        colors.box_blue,
        colors.box_alpha
    );
    const I420Color point_color = make_i420_color(
        colors.keypoint_red,
        colors.keypoint_green,
        colors.keypoint_blue,
        colors.keypoint_alpha
    );
    const I420Color skeleton_color = point_color;
    const I420Color background_color = make_i420_color(0, 0, 0, 210);
    const I420Color text_color = make_i420_color(255, 255, 255, 255);
    for (const OverlayRoi& roi : rois) {
        const I420Color roi_color = make_i420_color(
            roi.red,
            roi.green,
            roi.blue,
            roi.alpha
        );
        if (!std::isfinite(roi.left) || !std::isfinite(roi.top) ||
            !std::isfinite(roi.right) || !std::isfinite(roi.bottom)) {
            continue;
        }
        const float safe_left = std::max(0.0f, std::min(1.0f,
            std::min(roi.left, roi.right)));
        const float safe_right = std::max(0.0f, std::min(1.0f,
            std::max(roi.left, roi.right)));
        const float safe_top = std::max(0.0f, std::min(1.0f,
            std::min(roi.top, roi.bottom)));
        const float safe_bottom = std::max(0.0f, std::min(1.0f,
            std::max(roi.top, roi.bottom)));
        const int left = static_cast<int>(std::lround(
            safe_left * static_cast<float>(width - 1)
        ));
        const int right = static_cast<int>(std::lround(
            safe_right * static_cast<float>(width - 1)
        ));
        const int top = static_cast<int>(std::lround(
            safe_top * static_cast<float>(height - 1)
        ));
        const int bottom = static_cast<int>(std::lround(
            safe_bottom * static_cast<float>(height - 1)
        ));
        draw_i420_line(
            i420, width, height, left, top, right, top,
            roi_thickness, roi_color
        );
        draw_i420_line(
            i420, width, height, right, top, right, bottom,
            roi_thickness, roi_color
        );
        draw_i420_line(
            i420, width, height, right, bottom, left, bottom,
            roi_thickness, roi_color
        );
        draw_i420_line(
            i420, width, height, left, bottom, left, top,
            roi_thickness, roi_color
        );

        if (roi_font_scale <= 0) continue;
        std::string label = "ROI: " + (roi.name.empty() ? "ROI" : roi.name);
        const size_t maximum_characters = static_cast<size_t>(std::max(
            1,
            (width - 6) / (6 * roi_font_scale)
        ));
        if (label.size() > maximum_characters) {
            label.resize(maximum_characters);
        }
        const int label_width =
            static_cast<int>(label.size()) * 6 * roi_font_scale + 6;
        const int label_height = 7 * roi_font_scale + 6;
        const int label_left = std::max(
            0,
            std::min(width - label_width, left)
        );
        const int label_top = top >= label_height + 2
            ? top - label_height - 2
            : std::min(height - label_height, top + 2);
        fill_i420_rectangle(
            i420,
            width,
            height,
            label_left,
            label_top,
            label_left + label_width,
            label_top + label_height,
            background_color
        );
        draw_i420_text(
            i420,
            width,
            height,
            label_left + 3,
            label_top + 3,
            label,
            roi_font_scale,
            text_color
        );
    }
    for (const OverlayDetection& detection : detections) {
        if (!std::isfinite(detection.left) ||
            !std::isfinite(detection.top) ||
            !std::isfinite(detection.right) ||
            !std::isfinite(detection.bottom)) {
            continue;
        }
        const int left = static_cast<int>(std::lround(std::min(
            detection.left,
            detection.right
        )));
        const int right = static_cast<int>(std::lround(std::max(
            detection.left,
            detection.right
        )));
        const int top = static_cast<int>(std::lround(std::min(
            detection.top,
            detection.bottom
        )));
        const int bottom = static_cast<int>(std::lround(std::max(
            detection.top,
            detection.bottom
        )));
        draw_i420_line(
            i420, width, height, left, top, right, top,
            box_thickness, box_color
        );
        draw_i420_line(
            i420, width, height, right, top, right, bottom,
            box_thickness, box_color
        );
        draw_i420_line(
            i420, width, height, right, bottom, left, bottom,
            box_thickness, box_color
        );
        draw_i420_line(
            i420, width, height, left, bottom, left, top,
            box_thickness, box_color
        );

        for (const SkeletonEdge& edge : skeleton_edges) {
            if (edge[0] < 0 || edge[1] < 0 ||
                static_cast<size_t>(edge[0]) >= detection.keypoints.size() ||
                static_cast<size_t>(edge[1]) >= detection.keypoints.size()) {
                continue;
            }
            const OverlayKeypoint& first =
                detection.keypoints[static_cast<size_t>(edge[0])];
            const OverlayKeypoint& second =
                detection.keypoints[static_cast<size_t>(edge[1])];
            if (first.confidence < kKeypointThreshold ||
                second.confidence < kKeypointThreshold) {
                continue;
            }
            draw_i420_line(
                i420,
                width,
                height,
                static_cast<int>(std::lround(first.x)),
                static_cast<int>(std::lround(first.y)),
                static_cast<int>(std::lround(second.x)),
                static_cast<int>(std::lround(second.y)),
                skeleton_thickness,
                skeleton_color
            );
        }
        for (const OverlayKeypoint& point : detection.keypoints) {
            if (point.confidence < kKeypointThreshold ||
                !std::isfinite(point.x) ||
                !std::isfinite(point.y)) {
                continue;
            }
            draw_i420_disc(
                i420,
                width,
                height,
                static_cast<int>(std::lround(point.x)),
                static_cast<int>(std::lround(point.y)),
                point_radius,
                point_color
            );
        }

        char label_buffer[96]{};
        if (detection.track_id >= 0) {
            std::snprintf(
                label_buffer,
                sizeof(label_buffer),
                "T%d C%d %.2f",
                detection.track_id,
                detection.class_index,
                detection.confidence
            );
        } else {
            std::snprintf(
                label_buffer,
                sizeof(label_buffer),
                "C%d %.2f",
                detection.class_index,
                detection.confidence
            );
        }
        const std::string label(label_buffer);
        const int label_width =
            static_cast<int>(label.size()) * 6 * font_scale + 6;
        const int label_height = 7 * font_scale + 6;
        const int label_left = std::max(0, std::min(width - 1, left));
        const int label_top = top >= label_height + 2
            ? top - label_height - 2
            : std::max(0, std::min(height - label_height, top + 2));
        fill_i420_rectangle(
            i420,
            width,
            height,
            label_left,
            label_top,
            std::min(width, label_left + label_width),
            std::min(height, label_top + label_height),
            background_color
        );
        draw_i420_text(
            i420,
            width,
            height,
            label_left + 3,
            label_top + 3,
            label,
            font_scale,
            text_color
        );
    }
    return true;
}

void encode_annotated_frames(
    const std::string& output_path,
    int width,
    int height,
    int rotation_degrees,
    double frame_rate,
    AnnotatedFrameQueue& queue,
    EncoderStats& stats
) {
    const auto encoder_start = std::chrono::steady_clock::now();
    const bool succeeded = [&]() -> bool {
        if (output_path.empty()) {
            stats.error = "The native annotated-video path is empty.";
            return false;
        }
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            stats.error =
                "Native H.264 output requires positive even source dimensions.";
            return false;
        }
        if (!std::isfinite(frame_rate) || frame_rate <= 0.0) {
            frame_rate = 30.0;
        }
        const int32_t integer_frame_rate = static_cast<int32_t>(
            std::max(1.0, std::min(120.0, std::round(frame_rate)))
        );
        const int64_t pixels_per_second =
            static_cast<int64_t>(width) * static_cast<int64_t>(height) *
            static_cast<int64_t>(integer_frame_rate);
        const int32_t bit_rate = static_cast<int32_t>(
            std::max<int64_t>(
                2'000'000,
                std::min<int64_t>(20'000'000, pixels_per_second / 2)
            )
        );

        ScopedFd output_fd;
        output_fd.value = open(
            output_path.c_str(),
            O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC,
            0644
        );
        if (output_fd.value < 0) {
            stats.error = "The native pipeline could not create the annotated MP4.";
            return false;
        }

        MuxerGuard muxer;
        muxer.value = AMediaMuxer_new(
            output_fd.value,
            AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4
        );
        if (muxer.value == nullptr) {
            stats.error = "The native pipeline could not create an MP4 muxer.";
            return false;
        }
        int normalized_rotation = rotation_degrees % 360;
        if (normalized_rotation < 0) normalized_rotation += 360;
        if (normalized_rotation != 0 &&
            normalized_rotation != 90 &&
            normalized_rotation != 180 &&
            normalized_rotation != 270) {
            normalized_rotation = 0;
        }
        if (AMediaMuxer_setOrientationHint(
                muxer.value,
                normalized_rotation
            ) != AMEDIA_OK) {
            stats.error = "The native MP4 muxer rejected the video orientation.";
            return false;
        }

        FormatPtr format(AMediaFormat_new());
        if (!format) {
            stats.error = "The native pipeline could not allocate an encoder format.";
            return false;
        }
        AMediaFormat_setString(
            format.get(),
            AMEDIAFORMAT_KEY_MIME,
            "video/avc"
        );
        AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format.get(), AMEDIAFORMAT_KEY_HEIGHT, height);
        AMediaFormat_setInt32(
            format.get(),
            AMEDIAFORMAT_KEY_BIT_RATE,
            bit_rate
        );
        AMediaFormat_setInt32(
            format.get(),
            AMEDIAFORMAT_KEY_FRAME_RATE,
            integer_frame_rate
        );
        AMediaFormat_setInt32(
            format.get(),
            AMEDIAFORMAT_KEY_I_FRAME_INTERVAL,
            1
        );

        constexpr std::array<EncoderInputCandidate, 3> input_candidates{{
            {kColorFormatYuv420SemiPlanar, EncoderInputLayout::NV12},
            {kColorFormatYuv420Planar, EncoderInputLayout::I420},
            {kColorFormatYuv420Flexible, EncoderInputLayout::NV12}
        }};
        CodecGuard encoder;
        EncoderInputLayout encoder_input_layout = EncoderInputLayout::NV12;
        media_status_t last_configure_status = AMEDIA_ERROR_UNKNOWN;
        media_status_t last_start_status = AMEDIA_ERROR_UNKNOWN;
        for (const EncoderInputCandidate& candidate : input_candidates) {
            AMediaFormat_setInt32(
                format.get(),
                AMEDIAFORMAT_KEY_COLOR_FORMAT,
                candidate.color_format
            );
            AMediaCodec* candidate_encoder =
                AMediaCodec_createEncoderByType("video/avc");
            if (candidate_encoder == nullptr) continue;
            last_configure_status = AMediaCodec_configure(
                candidate_encoder,
                format.get(),
                nullptr,
                nullptr,
                AMEDIACODEC_CONFIGURE_FLAG_ENCODE
            );
            if (last_configure_status != AMEDIA_OK) {
                AMediaCodec_delete(candidate_encoder);
                continue;
            }
            last_start_status = AMediaCodec_start(candidate_encoder);
            if (last_start_status != AMEDIA_OK) {
                AMediaCodec_delete(candidate_encoder);
                continue;
            }
            encoder.value = candidate_encoder;
            encoder.started = true;
            encoder_input_layout = candidate.layout;
            break;
        }
        if (encoder.value == nullptr) {
            stats.error =
                "The native H.264 encoder rejected supported YUV420 layouts "
                "(configure " + std::to_string(last_configure_status) +
                ", start " + std::to_string(last_start_status) + ").";
            return false;
        }

        ssize_t track_index = -1;
        auto drain_encoder = [&](bool end_of_stream) -> bool {
            int idle_polls = 0;
            while (true) {
                AMediaCodecBufferInfo info{};
                const ssize_t output_index = AMediaCodec_dequeueOutputBuffer(
                    encoder.value,
                    &info,
                    kCodecTimeoutUs
                );
                if (output_index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                    if (!end_of_stream) return true;
                    if (++idle_polls >= kMaxEndOfStreamPolls) {
                        stats.error =
                            "Timed out finalizing the native H.264 stream.";
                        return false;
                    }
                    continue;
                }
                if (output_index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxer.started) {
                        stats.error =
                            "The native H.264 output format changed twice.";
                        return false;
                    }
                    FormatPtr output_format(
                        AMediaCodec_getOutputFormat(encoder.value)
                    );
                    if (!output_format) {
                        stats.error =
                            "The native encoder returned no output format.";
                        return false;
                    }
                    track_index = AMediaMuxer_addTrack(
                        muxer.value,
                        output_format.get()
                    );
                    if (track_index < 0 ||
                        AMediaMuxer_start(muxer.value) != AMEDIA_OK) {
                        stats.error =
                            "The native MP4 muxer could not start its video track.";
                        return false;
                    }
                    muxer.started = true;
                    continue;
                }
                if (output_index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (output_index < 0) continue;

                size_t capacity = 0;
                uint8_t* output_buffer = AMediaCodec_getOutputBuffer(
                    encoder.value,
                    static_cast<size_t>(output_index),
                    &capacity
                );
                const bool codec_config =
                    (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0;
                const bool output_eos =
                    (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                if (info.size > 0 && !codec_config) {
                    const int64_t buffer_end =
                        static_cast<int64_t>(info.offset) +
                        static_cast<int64_t>(info.size);
                    if (output_buffer == nullptr ||
                        info.offset < 0 ||
                        info.size < 0 ||
                        buffer_end > static_cast<int64_t>(capacity) ||
                        !muxer.started ||
                        track_index < 0) {
                        AMediaCodec_releaseOutputBuffer(
                            encoder.value,
                            static_cast<size_t>(output_index),
                            false
                        );
                        stats.error =
                            "The native encoder returned an invalid output buffer.";
                        return false;
                    }
                    if (AMediaMuxer_writeSampleData(
                            muxer.value,
                            static_cast<size_t>(track_index),
                            output_buffer,
                            &info
                        ) != AMEDIA_OK) {
                        AMediaCodec_releaseOutputBuffer(
                            encoder.value,
                            static_cast<size_t>(output_index),
                            false
                        );
                        stats.error =
                            "The native MP4 muxer could not write a video sample.";
                        return false;
                    }
                }
                if (AMediaCodec_releaseOutputBuffer(
                        encoder.value,
                        static_cast<size_t>(output_index),
                        false
                    ) != AMEDIA_OK) {
                    stats.error =
                        "The native encoder could not release an output buffer.";
                    return false;
                }
                idle_polls = 0;
                if (output_eos) return true;
            }
        };

        auto await_input_buffer = [&]() -> ssize_t {
            for (int attempt = 0; attempt < kMaxInputPolls; ++attempt) {
                const ssize_t input_index = AMediaCodec_dequeueInputBuffer(
                    encoder.value,
                    kCodecTimeoutUs
                );
                if (input_index >= 0) return input_index;
                if (!drain_encoder(false)) return -1;
            }
            stats.error =
                "Timed out waiting for a native H.264 encoder input buffer.";
            return -1;
        };

        const size_t expected_i420_size =
            static_cast<size_t>(width) * static_cast<size_t>(height) * 3u / 2u;
        std::vector<uint8_t> nv12_frame;
        int64_t last_output_time_us = -1;
        AnnotatedI420Frame frame;
        while (true) {
            const auto queue_wait_start = std::chrono::steady_clock::now();
            const bool has_frame = queue.pop(frame);
            stats.queue_wait_time +=
                std::chrono::steady_clock::now() - queue_wait_start;
            if (!has_frame) break;

            if (frame.i420.size() != expected_i420_size) {
                stats.error =
                    "The native H.264 encoder received an invalid I420 frame.";
                return false;
            }
            const std::vector<uint8_t>* encoder_frame = &frame.i420;
            if (encoder_input_layout == EncoderInputLayout::NV12) {
                if (!i420_to_nv12(
                        frame.i420,
                        width,
                        height,
                        nv12_frame,
                        stats.error
                    )) {
                    return false;
                }
                encoder_frame = &nv12_frame;
            }

            const ssize_t input_index = await_input_buffer();
            if (input_index < 0) return false;
            size_t input_capacity = 0;
            uint8_t* input_buffer = AMediaCodec_getInputBuffer(
                encoder.value,
                static_cast<size_t>(input_index),
                &input_capacity
            );
            if (input_buffer == nullptr ||
                input_capacity < encoder_frame->size()) {
                stats.error =
                    "The native H.264 encoder input buffer is too small for "
                    "the negotiated YUV420 layout.";
                return false;
            }
            std::memcpy(
                input_buffer,
                encoder_frame->data(),
                encoder_frame->size()
            );
            int64_t output_time_us =
                static_cast<int64_t>(stats.frames_encoded) * 1'000'000LL /
                static_cast<int64_t>(integer_frame_rate);
            if (output_time_us <= last_output_time_us) {
                output_time_us = last_output_time_us + 1;
            }
            if (AMediaCodec_queueInputBuffer(
                    encoder.value,
                    static_cast<size_t>(input_index),
                    0,
                    encoder_frame->size(),
                    static_cast<uint64_t>(output_time_us),
                    0
                ) != AMEDIA_OK) {
                stats.error =
                    "The native H.264 encoder rejected an annotated frame.";
                return false;
            }
            last_output_time_us = output_time_us;
            ++stats.frames_encoded;
            if (!drain_encoder(false)) return false;
        }

        if (stats.frames_encoded <= 0) {
            stats.error = "No annotated frames reached the native H.264 encoder.";
            return false;
        }
        const ssize_t eos_input_index = await_input_buffer();
        if (eos_input_index < 0) return false;
        const int64_t frame_interval_us = std::max<int64_t>(
            1,
            1'000'000LL / integer_frame_rate
        );
        if (AMediaCodec_queueInputBuffer(
                encoder.value,
                static_cast<size_t>(eos_input_index),
                0,
                0,
                static_cast<uint64_t>(last_output_time_us + frame_interval_us),
                AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
            ) != AMEDIA_OK ||
            !drain_encoder(true)) {
            if (stats.error.empty()) {
                stats.error =
                    "The native H.264 encoder could not finalize the stream.";
            }
            return false;
        }
        if (!muxer.started || track_index < 0) {
            stats.error = "The native MP4 contains no encoded video track.";
            return false;
        }
        const media_status_t stop_status = AMediaMuxer_stop(muxer.value);
        muxer.started = false;
        if (stop_status != AMEDIA_OK) {
            stats.error =
                "The native MP4 muxer could not finalize the file (error " +
                std::to_string(stop_status) + ").";
            return false;
        }
        stats.completed = true;
        return true;
    }();

    const auto total_time = std::chrono::steady_clock::now() - encoder_start;
    const auto accounted_time =
        stats.queue_wait_time + stats.color_conversion_time;
    stats.codec_time = total_time > accounted_time
        ? total_time - accounted_time
        : std::chrono::steady_clock::duration::zero();
    if (!succeeded) {
        queue.cancel();
        unlink(output_path.c_str());
    }
}

}  // namespace integrapose::media
