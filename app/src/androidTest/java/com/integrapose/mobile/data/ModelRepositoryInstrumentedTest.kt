package com.integrapose.mobile.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.integrapose.mobile.model.ModelOutputFormat
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.ui.MainViewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ModelRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var registryFile: File
    private lateinit var modelDirectory: File
    private lateinit var sourceModel: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        registryFile = File(context.filesDir, "model_registry.json")
        modelDirectory = File(context.filesDir, "models")
        sourceModel = File(context.cacheDir, "repository_test.onnx")
        cleanRepositoryFiles()
    }

    @After
    fun tearDown() {
        cleanRepositoryFiles()
    }

    @Test
    fun successfulImportCommitsReadableRegistryWithoutTemporaryFile() = runBlocking {
        sourceModel.writeBytes(byteArrayOf(1, 2, 3, 4))
        val sourceUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            sourceModel
        )

        val added = ModelRepository(context).addModel(
            sourceUri = sourceUri,
            name = "Instrumentation model",
            type = ModelType.DETECTION,
            inputSize = 640,
            confThreshold = 0.25f,
            iouThreshold = 0.45f,
            classNames = listOf("subject"),
            outputFormat = ModelOutputFormat.RAW_PREDICTIONS
        )

        val loaded = ModelRepository(context).listModels()
        assertEquals(listOf(added.id), loaded.map { it.id })
        assertTrue(registryFile.readText(Charsets.UTF_8).contains(added.id))
        assertFalse(File(registryFile.path + ".new").exists())
    }

    @Test
    fun interruptedAtomicWriteKeepsLastCommittedRegistry() {
        val emptyRegistryJson = "{${34.toChar()}models${34.toChar()}:[]}"
        registryFile.writeText(emptyRegistryJson, Charsets.UTF_8)
        val unfinishedOutput = AtomicFile(registryFile).startWrite()
        unfinishedOutput.write(
            "{${34.toChar()}models${34.toChar()}:[".toByteArray(Charsets.UTF_8)
        )
        unfinishedOutput.fd.sync()
        unfinishedOutput.close()

        val loaded = runBlocking { ModelRepository(context).listModels() }

        assertTrue(loaded.isEmpty())
        assertEquals(emptyRegistryJson, registryFile.readText(Charsets.UTF_8))
    }

    @Test
    fun corruptCommittedRegistryIsReportedAndPreserved() {
        registryFile.writeText("{broken", Charsets.UTF_8)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { ModelRepository(context).listModels() }
        }

        assertTrue(failure.message.orEmpty().contains("registry is unreadable"))
        assertEquals("{broken", registryFile.readText(Charsets.UTF_8))
    }

    @Test
    fun corruptRegistryIsSurfacedByViewModelWithoutCrashing() {
        registryFile.writeText("{broken", Charsets.UTF_8)
        val viewModel = MainViewModel(context.applicationContext as Application)

        val message = runBlocking {
            withTimeout(5_000L) {
                viewModel.uiState.map { it.message }.filterNotNull().first()
            }
        }

        assertTrue(message.contains("registry is unreadable"))
        assertEquals("{broken", registryFile.readText(Charsets.UTF_8))
    }

    @Test
    @Suppress("DEPRECATION")
    fun manifestKeepsOfflineDataBoundaries() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS
        )
        val applicationInfo = assertNotNull(packageInfo.applicationInfo).let {
            packageInfo.applicationInfo!!
        }
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val provider = packageInfo.providers.orEmpty().single {
            it.authority == "${context.packageName}.files"
        }

        assertFalse(requestedPermissions.contains(Manifest.permission.INTERNET))
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    private fun cleanRepositoryFiles() {
        registryFile.delete()
        File(registryFile.path + ".bak").delete()
        File(registryFile.path + ".new").delete()
        modelDirectory.deleteRecursively()
        sourceModel.delete()
    }
}
