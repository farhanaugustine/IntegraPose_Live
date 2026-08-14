package com.integrapose.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.integrapose.mobile.ui.MainAppScreen
import com.integrapose.mobile.ui.MainViewModel
import com.integrapose.mobile.ui.theme.IntegraPoseTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntegraPoseTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}