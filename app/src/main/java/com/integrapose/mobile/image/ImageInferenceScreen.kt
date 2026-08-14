package com.integrapose.mobile.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.benchmark.BenchmarkResult
import com.integrapose.mobile.benchmark.DeviceProfile
import com.integrapose.mobile.benchmark.benchmarkModel
import com.integrapose.mobile.export.shareExport
import com.integrapose.mobile.inference.FrameInferenceResult
import com.integrapose.mobile.importing.OpenReadOnlyDocument
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.OverlayRenderer
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.live.CsvSessionWriter
import com.integrapose.mobile.model.InferenceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageInferenceScreen(
    selectedModel: InferenceModelConfig?,
    runner: ModelInferenceRunner,
    ncnnTuning: NcnnRuntimeTuning?,
    annotationStyle: AnnotationStyle
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { DeviceProfile.collect(context) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var inference by remember { mutableStateOf<FrameInferenceResult?>(null) }
    var benchmark by remember { mutableStateOf<BenchmarkResult?>(null) }
    var runtimeText by remember { mutableStateOf<String?>(null) }
    var outputText by remember { mutableStateOf<String?>(null) }
    var csvPath by remember { mutableStateOf<String?>(null) }
    var annotatedImagePath by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bitmapAtDisposal by rememberUpdatedState(sourceBitmap)

    DisposableEffect(Unit) {
        onDispose { bitmapAtDisposal?.recycle() }
    }

    LaunchedEffect(selectedModel?.id, ncnnTuning) {
        runtimeText = null
        selectedModel?.let { model ->
            runCatching { runner.describe(model, ncnnTuning) }
                .onSuccess { runtimeText = it.displayText }
                .onFailure { errorText = it.message }
        }
    }

    val picker = rememberLauncherForActivityResult(OpenReadOnlyDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                errorText = null
                try {
                    val decoded = decodeImage(context, uri)
                    sourceBitmap?.recycle()
                    sourceBitmap = decoded
                    selectedName = uri.lastPathSegment ?: "selected image"
                    inference = null
                    benchmark = null
                    outputText = null
                    csvPath = null
                    annotatedImagePath = null
                } catch (error: Throwable) {
                    errorText = error.message ?: "Unable to decode image."
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Image Inference & Benchmark", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE6EDF9))
        Text(
            "The benchmark detects this device and keeps the model input fixed at its declared size; dynamic models use your selected size (640 by default).",
            color = Color(0xFFB8C4D8)
        )
        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55172233))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.summary, color = Color(0xFFD7E4F5))
                Text(
                    "App memory class: ${profile.appMemoryClassMb} MB",
                    color = Color(0xFFB9CBE2)
                )
                runtimeText?.let { Text(it, color = Color(0xFFA9E2CE)) }
            }
        }

        if (selectedModel == null) {
            Text("Import and select a compatible model in Models first.", color = Color(0xFFFFB4B4))
            return@Column
        }

        Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy) {
            Text("Select Image")
        }
        selectedName?.let { Text("Selected: $it", color = Color(0xFFA9D6F5)) }

        sourceBitmap?.let { bitmap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Selected image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                Canvas(Modifier.fillMaxSize()) {
                    inference?.let { result ->
                        drawIntoCanvas { canvas ->
                            OverlayRenderer.draw(
                                canvas.nativeCanvas,
                                result,
                                size.width,
                                size.height,
                                annotationStyle = annotationStyle,
                                skeletonConnections = selectedModel.skeletonConnections
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorText = null
                            runCatching {
                                val result = runner.run(
                                    bitmap = bitmap,
                                    config = selectedModel,
                                    ncnnTuning = ncnnTuning
                                )
                                val csvWriter = CsvSessionWriter(context)
                                val csv = csvWriter.start(selectedModel.type, "image")
                                csvWriter.append(result, 0)
                                csvWriter.close()
                                val annotated = OverlayRenderer.renderBitmap(
                                    bitmap,
                                    result,
                                    annotationStyle = annotationStyle,
                                    skeletonConnections = selectedModel.skeletonConnections
                                )
                                val imageFile = saveAnnotatedImage(context, annotated)
                                annotated.recycle()
                                result to "CSV: ${csv.absolutePath}\nAnnotated image: ${imageFile.absolutePath}"
                            }.onSuccess { (result, paths) ->
                                inference = result
                                outputText = paths
                                csvPath = paths.substringAfter("CSV: ").substringBefore('\n')
                                annotatedImagePath = paths.substringAfter("Annotated image: ")
                            }.onFailure { errorText = it.message ?: "Image inference failed." }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Run Inference")
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            errorText = null
                            runCatching {
                                benchmarkModel(
                                    runner,
                                    bitmap,
                                    selectedModel,
                                    ncnnTuning = ncnnTuning
                                )
                            }
                                .onSuccess { benchmark = it }
                                .onFailure { errorText = it.message ?: "Benchmark failed." }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Benchmark")
                }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        inference?.let {
            Text(
                "Detections: ${it.detections.size} | total ${it.inferenceMs} ms | preprocess ${it.preprocessingMs} ms | postprocess ${it.postprocessingMs} ms",
                color = Color(0xFFD8E8F7)
            )
        }
        benchmark?.let {
            Text(
                String.format(
                    Locale.US,
                    "Benchmark: %d runs | median %d ms | p95 %d ms | %.2f FPS | %dx%d | %s",
                    it.iterations,
                    it.medianMs,
                    it.p95Ms,
                    it.estimatedFps,
                    it.inputWidth,
                    it.inputHeight,
                    it.backend
                ),
                color = Color(0xFFA8F0D3)
            )
        }
        outputText?.let { Text(it, color = Color(0xFFFFDFB2)) }
        if (csvPath != null || annotatedImagePath != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                csvPath?.let { path ->
                    Button(
                        onClick = {
                            runCatching { shareExport(context, path, "text/csv") }
                                .onFailure { errorText = it.message }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share CSV") }
                }
                annotatedImagePath?.let { path ->
                    Button(
                        onClick = {
                            runCatching { shareExport(context, path, "image/png") }
                                .onFailure { errorText = it.message }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share Image") }
                }
            }
        }
        errorText?.let { Text(it, color = Color(0xFFFFB2B2)) }
    }
}

private suspend fun decodeImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > MAX_IMAGE_EDGE) {
                decoder.setTargetSampleSize((largest + MAX_IMAGE_EDGE - 1) / MAX_IMAGE_EDGE)
            }
        }
    } else {
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input) ?: error("The selected file is not a readable image.")
        }
    }
}

private suspend fun saveAnnotatedImage(context: Context, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
    val directory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "IntegraPose Live"
    ).also { it.mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val file = File(directory, "annotated_$stamp.png")
    file.outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Could not save annotated image."
        }
    }
    file
}

private const val MAX_IMAGE_EDGE = 4_096
