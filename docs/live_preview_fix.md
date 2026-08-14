# Live preview coordinate and recording parity guide

This document records the Live camera overlay failure found on 2026-08-09, the
reason it happened, the correction, and the tests required to prevent it from
returning. Keep this guide with future CameraX, preview-layout, orientation, and
recording changes.

## User-visible failure

The same NCNN pose model produced correct boxes and keypoints during Offline
inference, but Live preview annotations were rotated and translated toward an
edge of the screen. Manual **Align** controls could not reliably correct it.

The most useful diagnostic was to record both outputs from the same Live
session:

- the annotated source-frame recording placed boxes and keypoints correctly;
- the on-screen PreviewView placed those same detections incorrectly.

That proved the model, preprocessing, output decoding, letterbox reversal, and
source-frame annotation renderer were correct. The defect existed only in the
final mapping from ImageAnalysis buffer coordinates to PreviewView coordinates.

## Root cause

Live analysis uses CameraX `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888` and the
CameraX `ImageProxy.toBitmap()` member. That conversion copies the complete,
unrotated analysis buffer. A portrait camera frame can therefore arrive as a
landscape-shaped bitmap such as `1280 x 960` while
`imageProxy.imageInfo.rotationDegrees` is `90`.

Detection boxes and keypoints remain in this raw, unrotated bitmap coordinate
system. The source `OutputTransform` must describe those coordinates exactly.

The faulty configuration was:

```kotlin
ImageProxyTransformFactory().apply {
    setUsingCropRect(false)
    setUsingRotationDegrees(true)
}
```

`setUsingRotationDegrees(true)` incorrectly declared that the inference
coordinates were already rotation-adjusted. `CoordinateTransform` then saw a
90-degree rotation in both the source and PreviewView transforms and cancelled
them. Portrait detections were mapped as though they were already in portrait
space even though their coordinates were still in the raw landscape buffer.

Observed faulty geometry:

```text
buffer=1280x960 rotation=90
sourceMatrix=[rotation 90]
previewMatrix=[rotation 90]
coordinateMatrix=[scale and translation only]
```

The correction in `LiveInferenceScreen.kt` is:

```kotlin
ImageProxyTransformFactory().apply {
    setUsingCropRect(false)
    setUsingRotationDegrees(false)
}
```

After the correction, the source transform remains in raw-buffer space and the
PreviewView target transform applies rotation exactly once:

```text
buffer=1280x960 rotation=90
sourceMatrix=[unrotated source normalization]
previewMatrix=[rotation 90]
coordinateMatrix=[rotation 90, scale, and translation]
```

## Required mapping pipeline

Do not rotate or rescale individual detections as an extra Live-only step.
Preserve this sequence:

1. Convert the complete ImageProxy to an unrotated bitmap.
2. Run model preprocessing and inference on that bitmap.
3. Map decoded predictions back through the model letterbox transform into the
   unrotated bitmap's pixel coordinates.
4. Obtain the source transform from `ImageProxyTransformFactory` with crop and
   rotation both disabled.
5. Obtain the destination transform from `PreviewView.outputTransform`.
6. Use CameraX `CoordinateTransform(source, destination)` to build the overlay
   matrix.
7. Draw boxes, keypoints, labels, and skeleton lines through that single matrix.

Front-camera mirroring belongs in the CameraX/PreviewView destination transform.
Do not mirror detections again when a valid coordinate matrix is available.

The generic fit-center fallback must not replace CameraX mapping while
PreviewView is still publishing its viewport/rotation transform. A stale or
generic fallback can make annotations appear rotated or shifted at the screen
edge. Retaining the last valid CameraX coordinate matrix briefly is safer than
switching coordinate systems between frames.

## Preview and recording parity

The shape of a Compose preview panel must never determine the scientific camera
crop. A landscape regression demonstrated why: a very short, ultra-wide Live
panel supplied its `PreviewView.viewPort` to the complete use-case group and
produced a raw recording of only `1280 x 124`, while the annotated analysis
stream was `1280 x 720`.

The safe design is the reverse:

1. Select a stable recording/capture aspect supported by Preview,
   ImageAnalysis, and VideoCapture.
2. Build one explicit CameraX ViewPort from that capture aspect and the current
   target rotation.
3. Share that ViewPort across Preview, ImageAnalysis, and VideoCapture so they
   refer to the same sensor region.
4. Size or letterbox the on-screen preview to show that complete region.
5. Never crop the recording merely to fill unused UI space.

The default user experience must be WYSIWYG: every edge visible in Live preview
is present in raw and annotated recordings. If an optional fill/crop display is
ever added, it must be clearly labelled as a display crop and show an explicit
recording-frame guide.

## Orientation behavior

Before recording, Live may follow portrait, reverse portrait, landscape, and
reverse landscape using Android's orientation listener and CameraX target
rotation. The whole Live layout should reflow so the preview remains useful.

At recording start:

1. snapshot the current snapped Surface rotation;
2. lock the Activity to that orientation;
3. keep Preview, ImageAnalysis, VideoCapture, and annotated-output transforms
   fixed for the session;
4. unlock only after Stop and all files have finalized.

Without this lock, rotating the emulator during recording recreated the
Activity, finalized CameraX with `ERROR_SOURCE_INACTIVE`, reset the UI to
**Start recording**, and bypassed the normal result handoff.

`MainActivity` handles `orientation`, `screenSize`, and `keyboardHidden`
configuration changes in place. This is required: unlocking after finalization
must reflow the layout without recreating the Activity and discarding the raw
and annotated **View** actions.

## Annotated output ordering

Do not draw labels on an unrotated sensor buffer and then rely on MP4 rotation
metadata. That rotates the finished label with the scene; at 180 degrees the
field of view appears correct but every label is upside down and anchored to the
wrong display corner.

The verified Live annotated path is:

1. intersect and even-trim the shared CameraX crop rectangle;
2. map that crop into the locked display rotation;
3. mirror the mapped crop when the front camera preview is mirrored;
4. draw the camera pixels through that matrix;
5. map boxes, keypoints, skeletons, and ROIs through the same matrix; and
6. draw text only after the scene is in display coordinates.

`OverlayRenderer.renderOrientedCropBitmap()` implements this without changing
model preprocessing, decoding, or source-coordinate inference. The annotated
MP4 is encoded in its final physical dimensions with no rotation hint. Verified
portrait output was `720 x 1280` for both raw and annotated video; verified
landscape output was `1280 x 720` for both.

## Front-camera mirror parity

`PreviewView` mirrors the front camera, while CameraX VideoCapture defaults to
an unmirrored recording. That creates a WYSIWYG failure even when overlay
coordinates are correct. Use `MirrorMode.MIRROR_MODE_ON_FRONT_ONLY` for raw
VideoCapture and apply the same horizontal transform in the oriented annotated
renderer. Rear-camera output remains unchanged.

The emulator frame-search check measured preview-versus-raw SSIM of `0.990`
without another flip and `0.685` with a flip after the correction. The
preview-versus-annotated result was `0.987` unflipped and `0.680` flipped.

## Live ROI editor parity

The ROI snapshot must not copy the full unrotated analysis buffer directly.
That buffer includes sensor pixels outside the shared ViewPort and can have a
different rotation/mirror convention from Live preview.

`LiveRoiViewport` now:

- supplies the editor with the visible cropped, rotated, and mirrored frame;
- converts existing full-buffer ROI rectangles into editor coordinates; and
- converts edited rectangles back into full inference coordinates for preview
  drawing and entry/exit/dwell analytics.

The conversion is covered for rotations 0, 90, 180, and 270 degrees with rear
and front mirror modes. Users may create and rename multiple ROIs. Names appear
in ROI CSV output and, when **ROI outlines in annotated MP4** is enabled, beside
the corresponding outline in the annotated recording.

## Portrait Live layout contract

Portrait Live uses one fixed-height first page. The 9:16 preview consumes all
space above the two compact control rows, which sit directly above bottom
navigation. Status messages overlay the preview rather than reducing it.

The screen is not scrollable before selectable outputs exist. After Stop, the
result card is appended below the first page and an **Outputs available** down
arrow appears at the bottom of the preview. Tapping the indicator scrolls to the
raw video, annotated video, and CSV actions. Do not place the result card back in
the first-page Column; doing so shrinks the product's primary Live surface.

## Reproduction and regression testing

The Android Emulator can feed a prerecorded file through the CameraX camera
stack. Start an AVD with a known video for both lenses, for example:

```text
emulator -avd <name> \
  -camera-back videofile:<absolute-video-path> \
  -camera-front videofile:<absolute-video-path>
```

This is preferable to sending the video directly through Offline inference
because it exercises CameraX sensor rotation, PreviewView crop/mirroring, the
ImageAnalysis buffer, and the exact Live overlay path.

Required checks after any camera or layout change:

- rear camera, portrait;
- front camera, portrait, including mirroring;
- rear camera, landscape;
- reverse portrait and reverse landscape where supported;
- boxes and keypoints aligned on the PreviewView;
- the same source-frame annotations aligned in the annotated MP4;
- raw and annotated files show the same field of view as the preview;
- front preview, raw video, and annotated video use the same mirror convention;
- annotation labels remain upright at every Surface rotation;
- Live ROI editing uses the same crop, rotation, and mirror as the preview;
- raw/annotated dimensions are standard and not derived from a narrow UI panel;
- rotating before Start updates the preview correctly;
- rotating during recording does not recreate or terminate the session;
- Stop produces viewable raw and annotated MP4 files plus the normal result
  actions;
- Live FPS and model-inference timing do not regress materially.

For a private diagnostic build, the `IntegraPoseLiveMap` log tag can record
buffer size, crop rectangle, rotation degrees, source matrix, PreviewView
dimensions, PreviewView matrix, and the final coordinate matrix at a throttled
interval.

The local screenshots and videos used during the original diagnosis are
intentionally excluded from the public source project. Future validation should
capture fresh evidence for the device, Android version, and camera orientation
being tested.

## Things that must remain untouched during a preview fix

Unless an independent Offline test proves a defect, do not rewrite:

- model input letterboxing;
- NCNN or ONNX tensor execution;
- raw/final detection-row decoding;
- non-maximum suppression;
- keypoint extraction;
- mapping from model input back to source-frame pixels;
- native Offline bbox/keypoint placement;
- the established Offline annotated-video renderer.

Those components were validated by the correctly aligned Offline and
source-frame annotated outputs. Live recording adds a display-orientation matrix
around the existing renderer; it must not alter the protected inference path.
Preview alignment changes should remain isolated to CameraX transforms,
viewport/orientation lifecycle, and UI presentation.
