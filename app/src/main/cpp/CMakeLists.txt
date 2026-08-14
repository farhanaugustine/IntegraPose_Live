cmake_minimum_required(VERSION 3.22.1)

project(integrapose_ncnn LANGUAGES CXX)

if(NOT DEFINED NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT must point to the extracted NCNN Android package.")
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED CONFIG)

add_library(
    integrapose_ncnn
    SHARED
    ncnn_jni.cpp
    ncnn_gpu_runtime.cpp
    native_annotated_encoder.cpp
    native_media_pipeline.cpp
)

find_library(android_library android REQUIRED)
find_library(media_ndk_library mediandk REQUIRED)

target_compile_features(integrapose_ncnn PRIVATE cxx_std_17)
target_compile_options(
    integrapose_ncnn
    PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
)
target_link_options(
    integrapose_ncnn
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,--gc-sections"
)
target_link_libraries(
    integrapose_ncnn
    PRIVATE
    ncnn
    ${android_library}
    ${media_ndk_library}
)
