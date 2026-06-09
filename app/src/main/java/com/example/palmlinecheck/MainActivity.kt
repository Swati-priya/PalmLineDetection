package com.example.palmlinecheck

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.palmlinecheck.ui.theme.PalmLinecheckTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PalmLinecheckTheme {
                PalmReaderApp()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PalmReaderApp() {
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val snackbarHostState = remember { SnackbarHostState() }
    // True only after launchPermissionRequest() has actually been called — not before.
    // This prevents the snackbar from triggering on the initial status read, where
    // shouldShowRationale is also false before any request has been made.
    var requestLaunched by remember { mutableStateOf(false) }

    // Auto-request on launch
    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
            requestLaunched = true
        }
    }

    // React to status changes — only after we've actually launched a request
    LaunchedEffect(cameraPermission.status) {
        if (!requestLaunched) return@LaunchedEffect
        if (cameraPermission.status.isGranted) return@LaunchedEffect

        if (cameraPermission.status.shouldShowRationale) {
            // First denial — request once more
            cameraPermission.launchPermissionRequest()
        } else {
            // Second denial (permanently denied) — show snackbar
            val result = snackbarHostState.showSnackbar(
                message = "Camera access is required to read your palm",
                actionLabel = "Open Settings",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                )
            }
        }
    }

    // Dismiss the snackbar if the user grants permission from Settings and returns
    LaunchedEffect(cameraPermission.status.isGranted) {
        if (cameraPermission.status.isGranted) {
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { _ ->
        PalmCaptureScreen(cameraPermissionGranted = cameraPermission.status.isGranted)
    }
}
