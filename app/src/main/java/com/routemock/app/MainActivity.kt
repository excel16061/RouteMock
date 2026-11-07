package com.routemock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.routemock.app.ui.RouteMockRoot
import com.routemock.app.ui.RouteMockTheme
import com.routemock.app.ui.RouteMockViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: RouteMockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RouteMockTheme {
                RouteMockRoot(viewModel)
            }
        }
    }
}
