package com.integrapose.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.BuildConfig

@Composable
fun AboutScreen(onBack: (() -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        onBack?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Settings")
            }
        }

        Text(
            text = "Legal & acknowledgements",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFE4ECF8),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "IntegraPose Live ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFC0CEE0)
        )

        LegalSectionTitle("Models and user content")
        Text(
            text = if (BuildConfig.BUNDLED_TEST_KIT) {
                "This internal test build contains validation assets that are not included in production releases."
            } else {
                "Production releases do not include model weights or model training and export software. Models and media are selected by the user."
            },
            color = Color(0xFFC8D3E3)
        )
        Text(
            text = "IntegraPose Live does not supply or license user-selected models. You are responsible for having the rights and permissions needed to use selected models, datasets, images, and videos. Technical compatibility with a file format or output layout does not imply affiliation, sponsorship, or endorsement.",
            color = Color(0xFFC8D3E3)
        )

        LegalSectionTitle("Warranty and responsibility")
        Text(
            text = "The app is provided as is, without warranties. You remain responsible for its use, imported content, exported results, and resulting decisions.",
            color = Color(0xFFC8D3E3)
        )

        LegalSectionTitle("Third-party software")
        Text(
            text = "IntegraPose Live includes the open-source runtime components acknowledged below. Each component remains governed by its own license. Tap a link to view the official project, license, or complete notices.",
            color = Color(0xFFC8D3E3)
        )

        THIRD_PARTY_NOTICES.forEach { notice ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0x55203145))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = notice.name,
                        color = Color(0xFFEAF1FA),
                        fontWeight = FontWeight.Medium
                    )
                    Text(text = notice.copyright, color = Color(0xFFC9D6E8))
                    Text(text = "License: ${notice.license}", color = Color(0xFFC9D6E8))
                    LegalLink("Project", notice.projectUrl) { uriHandler.openUri(it) }
                    LegalLink("License", notice.licenseUrl) { uriHandler.openUri(it) }
                    notice.noticesUrl?.let { url ->
                        LegalLink("Complete notices", url) { uriHandler.openUri(it) }
                    }
                }
            }
        }

        Text(
            text = "Product names and trademarks are the property of their respective owners.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAEBED3),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun LegalSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFFEBF2FF),
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LegalLink(label: String, url: String, onOpen: (String) -> Unit) {
    Text(
        text = "$label: $url",
        color = Color(0xFF9ED9FF),
        style = MaterialTheme.typography.bodySmall,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { onOpen(url) }
    )
}

private data class ThirdPartyNotice(
    val name: String,
    val copyright: String,
    val license: String,
    val projectUrl: String,
    val licenseUrl: String,
    val noticesUrl: String? = null
)

private val THIRD_PARTY_NOTICES = listOf(
    ThirdPartyNotice(
        name = "AndroidX and Jetpack Compose",
        copyright = "Copyright The Android Open Source Project",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/androidx/androidx",
        licenseUrl = "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
    ),
    ThirdPartyNotice(
        name = "Material Components for Android",
        copyright = "Copyright The Android Open Source Project",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/material-components/material-components-android",
        licenseUrl = "https://github.com/material-components/material-components-android/blob/master/LICENSE"
    ),
    ThirdPartyNotice(
        name = "Kotlin standard library, Coroutines, and Serialization",
        copyright = "Copyright JetBrains and Kotlin contributors",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/JetBrains/kotlin",
        licenseUrl = "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"
    ),
    ThirdPartyNotice(
        name = "ONNX Runtime Android",
        copyright = "Copyright Microsoft Corporation",
        license = "MIT License",
        projectUrl = "https://github.com/microsoft/onnxruntime",
        licenseUrl = "https://github.com/microsoft/onnxruntime/blob/main/LICENSE",
        noticesUrl = "https://github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt"
    ),
    ThirdPartyNotice(
        name = "ncnn",
        copyright = "Copyright Tencent and ncnn contributors",
        license = "BSD 3-Clause License",
        projectUrl = "https://github.com/Tencent/ncnn",
        licenseUrl = "https://github.com/Tencent/ncnn/blob/master/LICENSE.txt"
    )
) + listOf(
    ThirdPartyNotice(
        name = "Khronos glslang",
        copyright = "Copyright Khronos Group and glslang contributors",
        license = "BSD 3-Clause and included third-party terms",
        projectUrl = "https://github.com/KhronosGroup/glslang",
        licenseUrl = "https://github.com/KhronosGroup/glslang/blob/main/LICENSE.txt"
    ),
    ThirdPartyNotice(
        name = "LLVM libc++ for the Android NDK",
        copyright = "Copyright LLVM Project contributors",
        license = "Apache License 2.0 with LLVM Exceptions",
        projectUrl = "https://github.com/llvm/llvm-project",
        licenseUrl = "https://github.com/llvm/llvm-project/blob/main/LICENSE.TXT"
    ),
    ThirdPartyNotice(
        name = "Okio",
        copyright = "Copyright Square, Inc.",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/square/okio",
        licenseUrl = "https://github.com/square/okio/blob/master/LICENSE.txt"
    ),
    ThirdPartyNotice(
        name = "Google Java utility and annotation artifacts",
        copyright = "Copyright Google LLC",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/google/guava",
        licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"
    ),
    ThirdPartyNotice(
        name = "JetBrains Annotations",
        copyright = "Copyright JetBrains s.r.o.",
        license = "Apache License 2.0",
        projectUrl = "https://github.com/JetBrains/java-annotations",
        licenseUrl = "https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt"
    )
)
