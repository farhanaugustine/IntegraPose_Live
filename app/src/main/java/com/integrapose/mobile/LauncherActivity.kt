package com.integrapose.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.integrapose.mobile.data.AppDataStore
import kotlinx.coroutines.launch

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val accepted = AppDataStore(applicationContext).isAgreementAccepted()
            val target = if (accepted) SplashActivity::class.java else AgreementActivity::class.java
            startActivity(Intent(this@LauncherActivity, target))
            finish()
        }
    }
}