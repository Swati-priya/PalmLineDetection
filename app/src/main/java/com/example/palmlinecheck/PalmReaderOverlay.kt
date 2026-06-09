package com.example.palmlinecheck

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CLASS_COLORS = mapOf(
    "heart"      to Color(0xFF00E5FF),
    "head"       to Color(0xFF39FF14),
    "life"       to Color(0xFFFF2A6D),
    "heartline"  to Color(0xFF00E5FF),
    "headline"   to Color(0xFF39FF14),
    "lifeline"   to Color(0xFFFF2A6D),
    "heart_line" to Color(0xFF00E5FF),
    "head_line"  to Color(0xFF39FF14),
    "life_line"  to Color(0xFFFF2A6D)
)
private val FALLBACK_COLOR = Color(0xFFFFFFFF)

@Composable
fun PalmReaderOverlay(
    capturedBitmap: Bitmap,
    modifier: Modifier = Modifier,
    roboflowApiKey: String
) {
    val api         = remember { RoboflowPalmLineApi() }
    var predictions by remember { mutableStateOf<List<RoboflowPalmLineApi.LinePrediction>?>(null) }
    var statusText  by remember { mutableStateOf("Detecting palm lines… (first run may take ~30s)") }

    LaunchedEffect(capturedBitmap, roboflowApiKey) {
        statusText  = "Detecting palm lines…"
        predictions = null

        val result = withContext(Dispatchers.IO) {
            api.detect(capturedBitmap, roboflowApiKey)
        }

        statusText = when {
            result == null   -> "⚠ API call failed — check Logcat (tag: RoboflowApi)"
            result.isEmpty() -> "⚠ No lines detected — try a clearer palm photo"
            else -> result.joinToString(" · ") {
                "${it.className} ${(it.confidence * 100).toInt()}%"
            }
        }
        predictions = result
    }

    Box(modifier = modifier) {
        Image(
            bitmap = capturedBitmap.asImageBitmap(),
            contentDescription = "Captured Palm",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )

        Canvas(modifier = Modifier.matchParentSize()) {
            val cW     = size.width
            val cH     = size.height
            val stroke = Stroke(width = 5f, cap = StrokeCap.Round)

            predictions?.forEach { pred ->
                val color = CLASS_COLORS[pred.className] ?: FALLBACK_COLOR
                if (pred.points.size < 2) return@forEach
                drawPath(keypointPath(pred.points, cW, cH), color, style = stroke)
            }
        }

        Text(
            text       = statusText,
            modifier   = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp, start = 16.dp, end = 16.dp),
            color      = Color.White,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// Draws a smooth quadratic bezier through ordered keypoints.
private fun keypointPath(points: List<Pair<Float, Float>>, cW: Float, cH: Float): Path {
    if (points.size < 2) return Path()
    return Path().apply {
        moveTo(points.first().first * cW, points.first().second * cH)
        for (i in 1 until points.lastIndex) {
            val prev = points[i - 1]
            val curr = points[i]
            val midX = (prev.first  + curr.first)  / 2f * cW
            val midY = (prev.second + curr.second) / 2f * cH
            quadraticTo(prev.first * cW, prev.second * cH, midX, midY)
        }
        lineTo(points.last().first * cW, points.last().second * cH)
    }
}
