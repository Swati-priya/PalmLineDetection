package com.example.palmlinecheck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun PalmCaptureScreen(
    palmLabel: String = "Left Palm",
    onBackPressed: (() -> Unit)? = {},
    onContinue: (() -> Unit)? = null,
    cameraPermissionGranted: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var handLandmarkerResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showProcessing by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf(PalmValidationResult()) }

    val handLandmarkerHelper = remember { HandLandmarkerHelper(context) }
    val palmValidator = remember { PalmValidator() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { handLandmarkerHelper.close() }
    }

    if (capturedBitmap != null && showProcessing) {
        ProcessingScreen(capturedBitmap = capturedBitmap!!)
    } else if (capturedBitmap != null) {
        ResultScreen(
            capturedBitmap = capturedBitmap!!,
            palmLabel = palmLabel,
            onRetake = {
                capturedBitmap = null
                handLandmarkerResult = null
                showProcessing = false
                palmValidator.reset()
            },
            onContinue = onContinue
        )
    } else {
        CameraScreen(
            context = context,
            lifecycleOwner = lifecycleOwner,
            palmLabel = palmLabel,
            isProcessing = isProcessing,
            validationResult = validationResult,
            cameraPermissionGranted = cameraPermissionGranted,
            handLandmarkerHelper = handLandmarkerHelper,
            palmValidator = palmValidator,
            onBackPressed = onBackPressed,
            onValidationUpdate = { validationResult = it },
            onImageCaptured = { bitmap ->
                scope.launch {
                    isProcessing = true
                    val result = withContext(Dispatchers.Default) {
                        handLandmarkerHelper.detectHand(bitmap)
                    }
                    isProcessing = false
                    if (result?.landmarks().isNullOrEmpty()) {
                        Toast.makeText(context, "No hand detected. Please try again.", Toast.LENGTH_SHORT).show()
                    } else {
                        handLandmarkerResult = result
                        capturedBitmap = bitmap
                        showProcessing = true
                        delay(10_000)
                        showProcessing = false
                    }
                }
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                isProcessing = false
            }
        )
    }
}

// ─── Camera / Scan screen ───────────────────────────────────────────────────

@Composable
private fun CameraScreen(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    palmLabel: String,
    isProcessing: Boolean,
    validationResult: PalmValidationResult,
    cameraPermissionGranted: Boolean,
    handLandmarkerHelper: HandLandmarkerHelper,
    palmValidator: PalmValidator,
    onBackPressed: (() -> Unit)?,
    onValidationUpdate: (PalmValidationResult) -> Unit,
    onImageCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit
) {
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var prevValidation by remember { mutableStateOf(PalmValidationResult()) }
    val displayMetrics = context.resources.displayMetrics
    val screenW = displayMetrics.widthPixels
    val screenH = displayMetrics.heightPixels

    // PreviewView created once — never re-created on recomposition
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(validationResult) {
        if (prevValidation.isHandPresent && !validationResult.isHandPresent) {
            Toast.makeText(context, "Place your palm back in the frame", Toast.LENGTH_SHORT).show()
        } else if (prevValidation.isPalmFacingCamera && !validationResult.isPalmFacingCamera && validationResult.isHandPresent) {
            Toast.makeText(context, "Dorsal side detected — flip your hand", Toast.LENGTH_SHORT).show()
        }
        prevValidation = validationResult
    }

    // Camera binding runs ONCE per lifecycle owner — not on every recomposition
    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                provider = future.get()
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val bmp = imageProxyToBitmapForAnalysis(proxy)
                        if (bmp != null) {
                            val r = handLandmarkerHelper.detectHand(bmp)
                            onValidationUpdate(palmValidator.validate(r))
                        }
                    } catch (e: Exception) {
                        Log.e("PalmCapture", "Analysis failed", e)
                    } finally {
                        proxy.close()
                    }
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis
                )
            } catch (e: Exception) {
                Log.e("PalmCapture", "Camera binding failed", e)
                onError("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Full-screen camera preview — just displays the pre-created PreviewView ──
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ── Top bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBackPressed != null) {
                IconButton(onClick = onBackPressed) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Text(
                text = palmLabel,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }

        // ── Scan frame + capture button with explicit spacing ──
        // Layout: statusBar | topBar(~64dp) | 85dp | scanFrame | 30dp | button | 22dp | navBar
        val bracketColor = when {
            validationResult.isValid        -> Color(0xFF00E676)
            validationResult.readyToCapture -> Color(0xFFFFEB3B)
            validationResult.isHandPresent  -> Color(0xFFFF9800)
            else                            -> Color.White
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Space occupied by the top bar content (icon 48dp + 8dp*2 vertical padding)
            Spacer(Modifier.height(64.dp))
            // Gap: toolbar bottom → scan frame top
            Spacer(Modifier.height(75.dp))

            // ── Scan frame ──
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                // Frosted-glass inner panel
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .fillMaxHeight(0.92f)
                        .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                )

                // Corner bracket overlay
                CornerBracketsOverlay(
                    modifier = Modifier.fillMaxSize(),
                    bracketColor = bracketColor
                )

                // Hand type badge — top-left corner of frame
                if (validationResult.isHandPresent && validationResult.handLabel.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.70f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = validationResult.handLabel,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }

                // Dorsal warning — center of frame
                if (validationResult.isDorsal) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFD32F2F).copy(alpha = 0.92f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Back of Hand Detected",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Flip your hand to show your palm",
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Ready badge — top-center
                if (validationResult.isValid) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF00C853)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "✓  Ready to Capture",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Gap: scan frame bottom → capture button
            Spacer(Modifier.height(30.dp))

            // ── Capture button ──
            if (isProcessing) {
                CircularProgressIndicator(
                    color = Color(0xFF7C4DFF),
                    modifier = Modifier.size(72.dp),
                    strokeWidth = 4.dp
                )
            } else {
                val canCapture = cameraPermissionGranted && validationResult.readyToCapture
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clickable {
                            if (canCapture) {
                                imageCapture?.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = imageProxyToBitmap(image)
                                            image.close()
                                            onImageCaptured(cropToScreenRatio(bmp, screenW, screenH))
                                        }
                                        override fun onError(e: ImageCaptureException) {
                                            Log.e("PalmCapture", "Capture failed", e)
                                            onError("Capture failed: ${e.message}")
                                        }
                                    }
                                )
                            } else {
                                val msg = when {
                                    !cameraPermissionGranted             -> "Camera permission required"
                                    !validationResult.isHandPresent      -> "Place your palm in the frame"
                                    validationResult.isDorsal            -> "Flip your hand — back side detected"
                                    !validationResult.isPalmFacingCamera -> "Show your palm, not the back"
                                    !validationResult.isPalmFlat         -> "Keep your palm flat"
                                    else                                 -> "Hold your hand still"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Outer ring
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .border(
                                width = 4.dp,
                                color = if (canCapture) Color.White else Color.White.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                    )
                    // Inner fill
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(
                                color = if (canCapture) Color(0xFF7C4DFF) else Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                    )
                }
            }

            // Gap: capture button → navigation bar
            Spacer(Modifier.height(22.dp))
        }
    }
}

// ─── Processing / analysis screen ────────────────────────────────────────────

@Composable
private fun ProcessingScreen(capturedBitmap: Bitmap) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Captured image background
        Image(
            bitmap = capturedBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Dim overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.50f))
        )

        // Diagonal shimmer sweep
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val bandWidth = w * 0.55f
            val startX = shimmerProgress * (w + bandWidth) - bandWidth
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f),
                                Color.White.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + bandWidth, h)
                        )
                    )
            )
        }

        // Horizontal scan line sweeping downward
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val totalHeight = maxHeight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .offset(y = totalHeight * shimmerProgress)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFAB82FF),
                                Color(0xFFAB82FF),
                                Color(0xFF7C4DFF).copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Central progress indicator + label
        Text(
            text = "Analysing Palm\u2026",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ─── Result screen ──────────────────────────────────────────────────────────

@Composable
private fun ResultScreen(
    capturedBitmap: Bitmap,
    palmLabel: String,
    onRetake: () -> Unit,
    onContinue: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Full-screen image + palm lines ──
        PalmReaderOverlay(
            capturedBitmap = capturedBitmap,
            modifier = Modifier.fillMaxSize(),
            roboflowApiKey = "a2TPVPkougsrMb06ZPPf"
        )

        // ── Bottom gradient + action buttons ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onContinue ?: onRetake,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (onContinue != null) "Continue" else "Capture Another Palm",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }

            TextButton(onClick = onRetake) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Rescan",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // ── Top bar overlay ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.40f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onRetake) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = palmLabel,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
        }
    }
}

// ─── Corner bracket overlay ──────────────────────────────────────────────────

@Composable
private fun CornerBracketsOverlay(
    modifier: Modifier = Modifier,
    bracketColor: Color = Color.White,
    bracketSize: Dp = 58.dp
) {
    val tint = ColorFilter.tint(bracketColor)
    Box(modifier = modifier) {
        // bottom_corner.png is the bottom-left bracket.
        // Rotate 90° → top-left, 180° → top-right, 270° → bottom-right.
        Image(
            painter = painterResource(R.drawable.bottom_corner),
            contentDescription = null,
            modifier = Modifier.size(bracketSize).align(Alignment.BottomStart),
            contentScale = ContentScale.Fit,
            colorFilter = tint
        )
        Image(
            painter = painterResource(R.drawable.bottom_corner),
            contentDescription = null,
            modifier = Modifier.size(bracketSize).align(Alignment.TopStart).rotate(90f),
            contentScale = ContentScale.Fit,
            colorFilter = tint
        )
        Image(
            painter = painterResource(R.drawable.bottom_corner),
            contentDescription = null,
            modifier = Modifier.size(bracketSize).align(Alignment.TopEnd).rotate(180f),
            contentScale = ContentScale.Fit,
            colorFilter = tint
        )
        Image(
            painter = painterResource(R.drawable.bottom_corner),
            contentDescription = null,
            modifier = Modifier.size(bracketSize).align(Alignment.BottomEnd).rotate(270f),
            contentScale = ContentScale.Fit,
            colorFilter = tint
        )
    }
}

// ─── Image conversion helpers ─────────────────────────────────────────────────

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().also { it.postRotate(image.imageInfo.rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/**
 * Crops a bitmap to the screen's aspect ratio, replicating CameraX FILL_CENTER behaviour.
 * The sensor image is usually wider (4:3) while the screen is taller (9:16), so this crops
 * the excess height/width from the centre so the result matches exactly what the preview showed.
 */
private fun cropToScreenRatio(bitmap: Bitmap, screenW: Int, screenH: Int): Bitmap {
    val screenRatio = screenW.toFloat() / screenH.toFloat()
    val bmpRatio    = bitmap.width.toFloat() / bitmap.height.toFloat()
    return if (bmpRatio > screenRatio) {
        // Bitmap is wider than the screen ratio — crop sides
        val newW = (bitmap.height * screenRatio).toInt().coerceAtMost(bitmap.width)
        val x    = (bitmap.width - newW) / 2
        Bitmap.createBitmap(bitmap, x, 0, newW, bitmap.height)
    } else {
        // Bitmap is taller than the screen ratio — crop top/bottom
        val newH = (bitmap.width / screenRatio).toInt().coerceAtMost(bitmap.height)
        val y    = (bitmap.height - newH) / 2
        Bitmap.createBitmap(bitmap, 0, y, bitmap.width, newH)
    }
}

private fun imageProxyToBitmapForAnalysis(image: ImageProxy): Bitmap? {
    return try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().also { it.postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        Log.e("PalmCapture", "Failed to convert analysis frame", e)
        null
    }
}
