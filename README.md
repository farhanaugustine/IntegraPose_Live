# IntegraPose Live

IntegraPose Live is an on-device Android inference engine for user-supplied
detection and pose models. It supports live camera inference, still images,
offline video analysis, annotated recording, behavioral bout summaries, and
region-of-interest (ROI) analytics.

The app runs NCNN models on CPU or Vulkan GPU and ONNX models through ONNX
Runtime on CPU. Media and inference remain on the device unless the user
explicitly shares or exports an output.

> ‼️**IMPORTANT**

> IntegraPose Live does not include model weights, training code, or model-export
> software. Users are responsible for supplying compatible models and ensuring
> that they have permission to use those models and any selected media.

> ⚠️ [WARNING]

> This project is under active development. Features may be incomplete, contain
> errors, or change without notice. The current release has been developed and
> tested primarily on a Samsung Galaxy S23 Ultra; results on other Android
> devices may differ. Please read the in-app user agreement and the disclaimers
> below before using this app.

## Features

- Live detection and pose inference from an Android camera
- Inference on still images and prerecorded videos
- NCNN CPU and Vulkan GPU execution
- ONNX Runtime CPU execution
- Raw and annotated MP4 recording
- Per-frame detection and pose CSV export
- Class-agnostic IoU tracking
- Behavioral bout summaries
- Named rectangular ROI entry, exit, and dwell analytics
- Configurable pose skeletons and annotation appearance
- On-device benchmarking for supported NCNN configurations
- Local processing without an Android network permission

## Requirements

- Android 8.0 (API 26) or newer
- Sufficient free storage for models, videos, and generated outputs
- A compatible user-supplied ONNX model or NCNN model package

Vulkan acceleration depends on the device and model. NCNN CPU inference remains
available when Vulkan is unavailable or unsuitable.

## Install with Android Studio

IntegraPose Live is distributed as an Android Studio source project for
developers, advanced users, and technical research collaborators. The supported
installation method is to build the `debug` variant and deploy it to a phone
through Android Debug Bridge (ADB).

ADB installations do not require an Android Developer Console account, package
registration, a release keystore, or a downloadable release APK. Google
documents this developer and testing exception in its
[Android developer-verification FAQ](https://developer.android.com/developer-verification/guides/faq).

For complete toolchain and command-line details, also see
[BUILDING.md](BUILDING.md).

### What you need

- A Windows, macOS, Linux, or ChromeOS computer with internet access
- Access to this GitHub repository
- [Android Studio](https://developer.android.com/studio) from the official
  Android Developers website
- JDK 17 for this project
- An Android 8.0 (API 26) or newer phone
- A USB data cable; a charging-only cable cannot connect the phone to ADB
- Free computer and phone storage for the build, models, and generated media

The first Gradle sync downloads dependencies and can take several minutes.

### 1. Install Android Studio

1. Download Android Studio from the
   [official download page](https://developer.android.com/studio).
2. Run the installer and complete the standard setup wizard.
3. Allow Android Studio to install its recommended Android SDK and platform
   tools.
4. Do not create a new Android project; open or clone this repository instead.

This project also requires Android SDK Platform 35, Android NDK
`29.0.14206865`, and CMake `3.22.1`. Android Studio normally offers to install
missing components during project sync.

### 2. Get the project from GitHub

The repository owner must first give the user access if the repository is
private.

From the Android Studio welcome screen:

1. Select **Get from VCS**.
2. Choose **Git** and paste this repository's clone URL.
3. Sign in to GitHub if Android Studio requests authorization.
4. Choose a local destination and select **Clone**.

If the repository is already cloned or extracted, select **Open** and choose the
project folder containing `settings.gradle.kts`. Do not open only the `app`
subfolder.

### 3. Allow the project to sync

1. Trust the project only if it came from this repository or another source you
   recognize.
2. Wait for Android Studio to complete the Gradle sync.
3. Accept prompts to install the required SDK, NDK, or CMake components.
4. Wait until indexing and background downloads finish.

If Gradle reports an incompatible Java runtime, open **Settings > Build,
Execution, Deployment > Build Tools > Gradle** and select a JDK 17 installation
for **Gradle JDK**. Do not continue while Android Studio shows an unresolved
Gradle sync error.

### 4. Enable Developer Options and USB debugging

The exact menu names vary by phone manufacturer. On many Android phones:

1. Open **Settings > About phone**.
2. Find **Build number**. On Samsung phones, it is under
   **Settings > About phone > Software information**.
3. Tap **Build number** seven times and enter the phone PIN if requested.
4. Return to **Settings**, open **Developer options**, and enable
   **USB debugging**.

Connect the unlocked phone to the computer with a USB data cable. If the phone
asks whether to allow USB debugging, verify that it is your computer and select
**Allow**. Only authorize computers you trust.

Windows users may also need the phone manufacturer's USB driver. See Google's
[hardware-device setup guide](https://developer.android.com/studio/run/device.html)
for manufacturer-specific links and additional instructions.

### 5. Select the debug build variant

1. In Android Studio, open **View > Tool Windows > Build Variants**.
2. Find the `app` module and set **Active Build Variant** to `debug`.
3. In the top toolbar, select the `app` run configuration.

The debug variant is intended for development and direct ADB installation. You
do not need to generate a signed bundle or create a release keystore for this
installation method.

### 6. Connect and install the app

1. Connect the unlocked phone with the USB data cable.
2. Accept the **Allow USB debugging** prompt on the phone.
3. Wait for the phone to appear in Android Studio's device selector.
4. Select the phone, then click the green **Run** button or choose
   **Run > Run app**.
5. Wait while Android Studio builds the project, installs it through ADB, and
   launches IntegraPose Live.

The first build can take several minutes because Android Studio may still be
downloading dependencies. Keep the phone unlocked until installation completes.

If Android displays an additional installation confirmation, review it and
approve it only if you obtained the project from a source you trust.

### 7. After installation

The app remains installed after the USB cable is disconnected, and it can be
opened normally from the phone's app launcher. USB debugging may be disabled
again when it is no longer needed.

To install a future revision from the same computer:

1. Pull or download the updated repository.
2. Open it in Android Studio and let Gradle sync.
3. Connect the phone and run the `debug` variant again.

Android Studio normally replaces the existing debug installation while
preserving its app data. If the project is built on another computer, that
computer may use a different debug signing key. Android can then require the
existing app to be uninstalled first, which also removes the app's private data
and settings.

### Installation troubleshooting

- **The phone does not appear:** Unlock it, confirm that the cable supports
  data, try another USB port, and select a USB data-transfer mode if the phone
  offers one.
- **The device is unauthorized:** Check the phone for the USB-debugging
  authorization prompt. If it does not appear, revoke USB debugging
  authorizations in Developer Options, disconnect the cable, and reconnect it.
- **Windows cannot detect the phone:** Install the manufacturer's current USB
  driver using the links in Google's
  [hardware-device setup guide](https://developer.android.com/studio/run/device.html).
- **Gradle sync fails:** Confirm that the computer has internet access and that
  Android Studio is using JDK 17. Install any SDK, NDK, or CMake components
  named in the error.
- **USB debugging is unavailable:** A work-managed or school-managed phone may
  block Developer Options or ADB. Contact the device administrator or use an
  unmanaged compatible device.

These instructions create a debuggable research build for direct installation.
They are not a substitute for a signed public release or an app-store
distribution process.

## Quick start

1. Open **Models** and import a compatible ONNX file or NCNN package.
2. Review the detected task, input size, class names, and post-processing mode.
3. Set the maximum detection count if the imported model allows it.
4. Run **Benchmark** to evaluate the selected model on the current device.
5. Choose **Live**, **Image**, or **Offline** inference.
6. Optionally configure recording, pose skeleton, behavior, and ROI settings.
7. Review or share the generated videos and CSV files.

## Model compatibility

Compatibility is determined by the model's input, output, metadata, and tensor
layout--not by its training framework or model-family name. File format alone
does not guarantee compatibility.

IntegraPose Live supports detection and pose outputs only. Classification,
segmentation, oriented bounding boxes, heatmap-only pose outputs, and deployment
formats other than ONNX and NCNN are not supported.

| Runtime | Required files | Supported result layouts |
| --- | --- | --- |
| ONNX Runtime CPU | One `.onnx` file | Raw bbox/class candidate rows or final detection rows, with optional pose keypoint triplets |
| NCNN CPU or Vulkan | `model.ncnn.param`, `model.ncnn.bin`, and `metadata.yaml` | Raw bbox/class candidate rows, with optional pose keypoint triplets |

The app letterboxes images with a gray value of 114, reads channels in RGB
order, and scales pixel values to the `0.0`-`1.0` range. Models requiring BGR
input, mean subtraction, custom standardization, multiple image inputs, or other
preprocessing are not directly compatible with the current engine.

### ONNX contract

An ONNX model must provide:

- one four-dimensional image input;
- batch size 1, with RGB channels in NCHW or NHWC layout;
- a `FLOAT`, `FLOAT16`, or `BFLOAT16` input tensor;
- a positive fixed input size or a compatible dynamic input size; and
- a floating-point detection or pose tensor as its first output.

The first output must be a two- or three-dimensional tensor that can be
interpreted as candidate rows. Embedded export metadata is recommended so the
app can identify the task, input size, class names, and output format.

### NCNN package contract

Select a folder containing these exact filenames at the top level:

```text
model.ncnn.param
model.ncnn.bin
metadata.yaml
```

An NCNN package must also satisfy all of these requirements:

- the input blob is named `in0`;
- the output blob is named `out0`;
- the model accepts one square, three-channel RGB float input;
- the output is one two-dimensional raw candidate tensor;
- `metadata.yaml` is non-empty UTF-8 text;
- metadata declares `task: detect`, `task: detection`, or `task: pose`;
- the export uses `end2end: false`; and
- the export does not embed NMS (`nms: false`).

The input-size setting must be a multiple of 32 between 32 and 2048. The size
recorded in `metadata.yaml` should match the exported model.

A minimal detection metadata file has this form:

```yaml
model_name: Example detection model
task: detect
imgsz: [640, 640]
names:
  0: animal
end2end: false
nms: false
```

For pose models, also include the exported keypoint shape when available:

```yaml
kpt_shape: [12, 3]
```

Import validation checks the package files and metadata, but complete
compatibility can only be confirmed by running inference and reviewing the
results on representative media.

### Output row formats

Raw predictions use bbox center coordinates followed by one score per class and,
for pose models, zero or more keypoint triplets:

```text
[center_x, center_y, width, height, class_scores..., keypoint_x, keypoint_y, keypoint_confidence...]
```

Supported final ONNX detections use corner coordinates, confidence, class ID,
and optional keypoint triplets:

```text
[x1, y1, x2, y2, confidence, class_id, keypoint_x, keypoint_y, keypoint_confidence...]
```

Coordinates may use model pixels or normalized values. For raw predictions,
IntegraPose Live applies confidence filtering, class-aware NMS, and the
configured detection cap on the device.

### Export metadata and post-processing

| Export metadata | Expected output | App processing |
| --- | --- | --- |
| `end2end=true`, `nms=false` | Final detection rows | Confidence filtering and detection cap; no second NMS pass |
| `end2end=false`, `nms=true` | Final post-NMS rows | Confidence filtering and detection cap; no second NMS pass |
| `end2end=false`, `nms=false` | Raw predictions | Confidence filtering, NMS, and detection cap |

`end2end=true` and `nms=true` are mutually exclusive. NCNN packages must use
the raw-prediction contract with both values set to `false`.

### Detection count and multiple subjects

Detection count controls the maximum number of detections retained per frame.
Its default value is 1, but this is not a single-subject restriction. Increase
it when a model and experiment require multiple concurrent detections.

If a fixed detection count was embedded during export, the app locks the setting
to that value. Re-export the model to change it. For compatible raw-output
models without a fixed count, open **Models** and select **Set detection count**.

Detection count does not set the number of ROIs and does not change how behavior
bouts are constructed.

## Recording and analysis workflow

During Live recording, **Stop & save** stops accepting new inference frames,
finalizes the selected raw and annotated videos, and then builds the requested
CSV analytics. During Offline analysis, **Stop and save** finishes the active
inference frame and preserves any usable partial video and CSV outputs. Keep the
app on the active tab until the saving message completes.

Live video is streamed directly to app-owned storage rather than retained in
RAM. A positive planned duration triggers a conservative free-space check,
automatic stopping at the requested time, and continued monitoring of the disk
reserve. Use a duration of `0` to stop manually.

## Behavior and ROI outputs

Recording options can produce:

- a per-frame detection and pose CSV;
- mutually exclusive behavior bouts based on the highest-confidence class for
  each track and frame;
- named rectangular ROI entry, exit, and dwell events;
- raw and annotated MP4 recordings; and
- optional ROI names and outlines in the annotated MP4.

Tracking is class-agnostic, so a behavior-class change does not create a new
track identity. Tracking uses a basic IoU association method; person or animal
re-identification is not implemented.

### Defining ROIs

In **Offline**, select **Define regions** to draw ROIs over the video's first
frame. Use one finger to draw, move, or resize a region. Pinch with two fingers
to zoom, or move both fingers to pan.

In **Live**, open **Setup**, select **Define regions**, and capture the next
camera frame. Multiple ROIs can be created and renamed. The editor uses the same
cropped, oriented, and front-camera-mirrored view as Live preview.

**ROI outlines in annotated MP4** controls whether names and outlines are burned
into annotated video. Raw video is never annotated. ROI analytics can remain
enabled when outlines are hidden.

### ROI and annotation settings

Open **Settings > Annotation appearance > ROI name labels** to select **Off**,
**Small** (default), **Medium**, or **Large**. Turning labels off hides the ROI
name but leaves its colored outline and analytics active. Each ROI receives a
stable, automatic high-contrast color that does not change when it is renamed.

Numeric class IDs are hidden from annotations by default. Enable **Settings >
Annotation appearance > Show numeric class IDs** to display the model's
zero-based class number beside its label. Class IDs and names remain present in
CSV exports regardless of this display setting.

| Analytics setting | Default | Meaning |
| --- | ---: | --- |
| Maximum frame gap | 5 frames | Missing frames that may be bridged when the same behavior appears on both sides |
| Minimum bout duration | 3 frames | Shortest behavior bout included in the bout CSV |
| Maximum ROI gap | 5 frames | Missing or absent frames that may be bridged within one ROI visit |
| Minimum ROI dwell | 3 frames | Shortest ROI visit included in the dwell CSV |
| ROI entry threshold | 0.75 | Bounding-box overlap required to enter unless its center is inside |
| ROI exit threshold | 0.25 | Lower overlap used while inside to reduce boundary flicker |

Bout and visit start and end frames are inclusive. For example, frames 10
through 14 represent a duration of five frames. Shorter contacts remain in the
per-frame record but are omitted from the summarized event CSV.

For pose models, ROI membership can use a selected zero-based keypoint index.
Index `0` corresponds to `kpt1` in the CSV.

## Pose skeletons

After selecting a pose model, open **Settings > Pose skeleton** and enter
connections as zero-based keypoint pairs, such as `0-1, 1-2`. Use the keypoint
order from the model's training dataset. IntegraPose Live does not infer an
animal-specific skeleton. Leave the field empty to display keypoints without
connecting lines.

The saved skeleton applies to Live, Image, Offline, and exported annotated media
for that model.

## Benchmarking and performance

For NCNN models, **Benchmark CPU and Vulkan GPU** evaluates available execution
configurations on the current device. When Vulkan runs, the benchmark also
compares its predictions with CPU results. The automatic recommendation selects
Vulkan only when it passes the prediction-agreement check and outperforms the
eligible CPU path. Users may still select Vulkan manually after reviewing its
reported error profile.

The benchmark can recommend separate validated configurations for the ordered
Live/Image path and the pipelined Offline path. ONNX inference currently uses
its supported CPU path.

Performance depends on the model, input size, device, backend, camera, video
decoder and encoder, available memory, and device temperature. Extended
recording can heat a phone and increase inference latency. Use the built-in
benchmark and a sustained recording on representative media before relying on
a configuration for an experiment.

Some phones use power-saving settings that reduce sustained performance. Review
the device's battery and performance settings when benchmarking, while
considering the resulting power use and heat.

## Privacy

Camera frames and selected media are processed on the device. Source documents
are opened read-only. Imported models and selected cloud videos are copied into
app-owned storage before use; the source documents are not renamed, moved, or
deleted.

The app does not request Android network access, and Android cloud backup is
disabled. Imported models, recordings, and scientific outputs are therefore not
uploaded by the app or included in Android backup. Content leaves app-owned
storage only when the user explicitly chooses an Android share or export action.

Third-party software acknowledgements, license names, and official notice links
are available under **Settings > Legal & acknowledgements**.

## Known limitations

- The current release has been tested primarily on a Samsung Galaxy S23 Ultra.
- Long recordings may heat the device and increase inference latency.
- Tracking uses basic IoU association; re-identification is not implemented.
- Model import validation cannot prove runtime or prediction compatibility.
- ONNX inference currently uses CPU execution.
- Vulkan availability and prediction parity vary by device and model.
- Classification, segmentation, oriented-box, and heatmap-only outputs are not
  supported.

## License and disclaimer

No software license is currently granted for this repository. All rights are
reserved unless otherwise stated. A formal license may be added in a future
release.

This software is provided **AS IS**, without warranty of any kind, express or
implied, including but not limited to warranties of merchantability, fitness for
a particular purpose, and non-infringement.

By downloading, copying, installing, executing, or otherwise using any portion
of this repository, you acknowledge that you assume the risks associated with
its use. To the fullest extent permitted by applicable law, the authors,
contributors, and copyright holders are not liable for loss, damage, erroneous
results, data loss, or other consequences arising from use of or reliance on
this software.
