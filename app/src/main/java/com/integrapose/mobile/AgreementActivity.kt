package com.integrapose.mobile

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.integrapose.mobile.data.AppDataStore
import com.integrapose.mobile.ui.USER_CONTRACT_TEXT
import com.integrapose.mobile.ui.theme.IntegraPoseTheme
import kotlinx.coroutines.launch

class AgreementActivity : ComponentActivity() {
    private val requiredPermissions: Array<String>
        get() = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true

        if (cameraGranted) {
            acceptAgreementAndContinue()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required for live inference. Audio is optional.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            IntegraPoseTheme {
                Surface {
                    AgreementScreen(
                        onAccept = {
                            if (BuildConfig.REQUEST_SENSORS_AFTER_AGREEMENT) {
                                permissionLauncher.launch(requiredPermissions)
                            } else {
                                acceptAgreementAndContinue()
                            }
                        },
                        onDecline = {
                            finishAffinity()
                        }
                    )
                }
            }
        }
    }

    private fun acceptAgreementAndContinue() {
        lifecycleScope.launch {
            AppDataStore(applicationContext).setAgreementAccepted(true)
            startActivity(Intent(this@AgreementActivity, SplashActivity::class.java))
            finish()
        }
    }
}

@Composable
private fun AgreementScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var accepted by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF02070B), Color(0xFF11243C), Color(0xFF2E1A25))
                )
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "User Agreement",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE7EDF9)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0x99131F31), RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = USER_CONTRACT_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD7E0F0)
                )
            }

            Button(
                onClick = { accepted = !accepted },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x332A3B53)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Checkbox(checked = accepted, onCheckedChange = null, modifier = Modifier.size(20.dp))
                Text(
                    text = "I have read and agree to these terms",
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color(0xFFE4EAF4)
                )
            }

            Button(
                onClick = onAccept,
                enabled = accepted,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33B7FF))
            ) {
                Text("Accept and Continue", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            TextButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Decline", color = Color(0xFFFFA2A2))
            }
        }
    }
}
