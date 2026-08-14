# IntegraPose Live

IntegraPose Live runs high-speed detection and pose inference on Android using
models you select. It supports live camera use, images, videos, annotated
recording, and a focused set of behavior and region-of-interest (ROI) outputs.

Production releases do not include model weights or model training and export
software. You are responsible for having permission to use the models and media
you select.

## Build from source

This repository is the public Android Studio project. It includes the NCNN
Android Vulkan SDK libraries required by the native inference layer, but it
does not include model weights or test videos. See [BUILDING.md](BUILDING.md)
for the required Android SDK components and build commands.

## Main workflow

1. Import a supported model in **Models**.
2. Use **Benchmark** to measure the selected model on the current phone.
3. Optionally define named ROIs and choose whether ROI outlines appear in the
   annotated video.
4. Optionally enter a planned recording duration, then start live inference and
   record raw video, annotated video, or both.
5. Select **Stop & save**. IntegraPose Live then finalizes the recordings and builds
   requested bout and ROI analytics from the saved per-frame results.
6. Review or share the videos and CSV outputs.

Building analytics after recording keeps live inference and video capture as
responsive as possible.

Live video is streamed directly to app-owned disk storage rather than retained
in RAM. A positive planned duration performs a conservative free-space check,
stops automatically at the requested time, and keeps monitoring the remaining
disk reserve while recording. Use `0` for manual Stop.

During Live recording, **Stop & save** stops accepting session inference frames,
finalizes raw and annotated video, and then builds the selected CSV analytics.
During Offline analysis, **Stop and save** finishes the active inference frame
and finalizes any usable partial video and CSV outputs. Keep the app on that tab
until the saving message completes.

## Supported model files and outputs

| Format | Required files | Supported output |
| --- | --- | --- |
| ONNX | One `.onnx` file | Bbox detection rows or bbox-plus-keypoint pose rows |
| NCNN | `model.ncnn.param`, `model.ncnn.bin`, and `metadata.yaml` | Raw bbox/class candidate rows with optional keypoint triplets |

Compatibility is determined by the selected file format, task metadata, and
tensor layout, not by an upstream training framework or model-family name.
Segmentation, classification, oriented-box outputs, and deployment formats
other than ONNX and NCNN are not supported.

- **ONNX:** select one `.onnx` file. Embedded export metadata is recommended.
- **NCNN:** select a folder containing `model.ncnn.param`, `model.ncnn.bin`, and
  `metadata.yaml`. All three files are required and are preserved together.
  Metadata must identify detection or pose and must describe a raw-output
  package without embedded NMS or end-to-end final rows.

Android's picker opens cloud and local model sources read-only. IntegraPose Live
copies an imported model into private app storage and does not rename, move, or
delete the source document.

## Export settings

The app reads available model metadata and supports these output contracts:

| Export settings | Model output | Processing in the app |
| --- | --- | --- |
| `end2end=true`, `nms=false` | Final detection rows | Confidence filtering and the detection cap; no second NMS pass |
| `end2end=false`, `nms=true` | Final post-NMS rows | Confidence filtering and the detection cap; no second NMS pass |
| `end2end=false`, `nms=false` | Raw predictions | NMS on the phone, followed by the detection cap |

`end2end=true` and embedded `nms=true` are mutually exclusive. Supported
NCNN packages use the raw bbox/keypoint-row contract, so the app performs NMS
on the phone.

### Detection count and multiple animals

The default detection count is 1 for a one-subject frame, and the app clearly
displays that setting. It is not a single-animal restriction: use a larger value
when the model and experiment require multiple concurrent detections.

If a fixed detection count was baked into the exported graph, the app must use
that exact value and locks the setting. Re-export the model to change it. The
detection count remains editable for a raw-output model whose export did not fix
the output count.

After import, open **Models** and tap **Set detection count** on a
runtime-editable model to change the concurrent subject limit. The selected
value is shown again in Live, Offline, and Benchmark. Export-fixed models show
the fixed value and do not offer the button.

Detection count controls only the number of detections retained per frame. It
does not set the number of ROIs or change how behavior bouts are constructed.

## Behavior and ROI outputs

Recording options provide these focused outputs:

- per-frame detection and pose CSV;
- mutually exclusive behavior bouts, using the highest-confidence class for
  each track and frame;
- named rectangular ROI entry, exit, and dwell events;
- raw and annotated MP4 recordings; and
- optional ROI names and outlines burned into the annotated MP4.

Tracking is class-agnostic, so a behavior-class change does not create a new
animal identity. The ROI outline option affects only the annotated video; ROI
analytics can be enabled or disabled independently.

In **Offline**, choose **Define regions** to draw ROIs over the video's first
frame. Use one finger to draw, move, or resize a region; pinch with two fingers
to zoom and move both fingers to pan. **ROI outlines in annotated MP4** controls
whether the saved offline video includes the named outlines.

In **Live**, open **Setup**, choose **Define regions**, and capture the next
camera frame. Users may create and rename multiple ROIs. The editor shows the
same cropped, oriented, and front-camera-mirrored region as Live preview.
**ROI outlines in annotated MP4** is available after at least one ROI exists and
burns each selected outline and name into the annotated recording. Raw video is
never annotated.

ROI appearance is shared across the editor, Live preview, and annotated output.
Open **Settings > Annotation appearance > ROI name labels** to choose **Off**,
**Small** (default), **Medium**, or **Large**. **Off** hides only the ROI name;
the colored outline and ROI analytics remain active. Each ROI receives a stable
automatic high-contrast color, so renaming it does not change its color.

| Analytics setting | Default | Meaning |
| --- | ---: | --- |
| Maximum frame gap | 5 frames | Missing frames that may be bridged when the same behavior appears on both sides |
| Minimum bout duration | 3 frames | Shortest behavior bout included in the bout CSV |
| Maximum ROI gap | 5 frames | Missing or absent frames that may be bridged within one ROI visit |
| Minimum ROI dwell | 3 frames | Shortest ROI visit included in the dwell CSV |
| ROI entry threshold | 0.75 | Bounding-box overlap required to enter unless its center is inside |
| ROI exit threshold | 0.25 | Lower overlap used while inside to reduce boundary flicker |

Bout and visit start and end frames are inclusive. For example, frame 10
through frame 14 has a duration of 5 frames. Contacts shorter than the selected
minimum remain in the per-frame record but are omitted from the summarized
event CSV.

For pose models, ROI membership may use a selected zero-based keypoint index.
Index `0` corresponds to `kpt1` in the CSV.

## Pose skeletons

Open **Settings > Pose skeleton** after selecting a pose model. Enter connections
as zero-based keypoint pairs, such as `0-1, 1-2`, using the keypoint order from
the model's training dataset. IntegraPose Live does not guess an animal-specific
skeleton. Leave the field empty to display keypoints without connecting lines.

The saved skeleton applies to Live, Image, Offline, and exported annotated
media for that model.

## Benchmarking and performance

For NCNN models, **Benchmark CPU and Vulkan GPU** measures both processing
devices when Vulkan can run, compares their predictions on distributed video
frames, and then asks which device to use. Vulkan FPS remains visible when its
prediction-agreement check reports differences. The automatic recommendation
selects Vulkan only when it passes that check and beats the eligible CPU path;
you may still choose Vulkan manually after reviewing and accepting its error
profile. The inference CSV backend column records manual selection and Vulkan
parity status.

Public builds compare three actionable configurations over 30 frames per
trial. Internal timing-only tables, decoder tests, private models, and
thread/worker diagnostic surfaces are not included in this public project.
After one successful comparison, an accidental same-session rerun is disabled;
select another representative video to run again intentionally. Immediate
repeat tests may be slower when the phone is warm.

The NCNN comparison can recommend separate validated configurations for the
single ordered Live/Image path and the pipelined Offline path. ONNX inference
currently uses its supported CPU path.

Videos selected from a cloud provider are copied with a small streaming buffer
into the durable, app-owned **IntegraPose Live** disk library before Offline
or Benchmark starts. Neither path runs from an active cloud-provider link. A
configuration that exceeds available memory is skipped and reported without
discarding other successful benchmark results.

IntegraPose Live targets a 30 FPS or faster complete pipeline on validated
model/device combinations. Actual speed depends on the model, input size,
phone, backend, camera, video encoding, and device temperature. Use the
Benchmark results and a sustained live recording to evaluate the configuration
instead of assuming every model will reach the target.

## Privacy and legal acknowledgements

Camera and selected media are processed on the device. Picker sources are
opened read-only; imported models and selected cloud videos are copied into
app-owned disk storage before use. Generated outputs remain in app-scoped
storage until you choose to share them.

Third-party software acknowledgements, license names, and official notice links
are available under **Settings > Legal & acknowledgements**.
