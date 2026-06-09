package com.example.palmlinecheck

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RoboflowPalmLineApi {

    // Keypoints tracing a single palm line (head / heart / life).
    // Points are normalized 0–1 relative to the image sent to the API.
    data class LinePrediction(
        val className: String,
        val confidence: Float,
        val points: List<Pair<Float, Float>>   // keypoints in order along the line
    )

    // Model: palm-lines-recognition-ztknj/1 — keypoint detection, 10 pts per line
    private val endpoint = "https://serverless.roboflow.com/palm-lines-recognition-ztknj/1"

    suspend fun detect(bitmap: Bitmap, apiKey: String): List<LinePrediction>? =
        withContext(Dispatchers.IO) {
            try {
                val resized = resizeBitmap(bitmap, maxSide = 640)
                Log.d("RoboflowApi", "Sending image ${resized.width}x${resized.height}")

                val out = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base64Image = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                val url  = URL("$endpoint?api_key=$apiKey&confidence=20")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput       = true
                    connectTimeout = 30_000
                    readTimeout    = 60_000
                }

                conn.outputStream.use { it.write(base64Image.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "no body"
                    Log.e("RoboflowApi", "HTTP $code — $err")
                    return@withContext null
                }

                val json = conn.inputStream.bufferedReader().readText()
                Log.d("RoboflowApi", "Response: $json")
                parseResponse(json, resized.width.toFloat(), resized.height.toFloat())

            } catch (e: Exception) {
                Log.e("RoboflowApi", "Request failed", e)
                null
            }
        }

    private fun resizeBitmap(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun parseResponse(json: String, fallbackW: Float, fallbackH: Float): List<LinePrediction> {
        val root        = JSONObject(json)
        val imgW        = root.optJSONObject("image")?.getDouble("width")?.toFloat()  ?: fallbackW
        val imgH        = root.optJSONObject("image")?.getDouble("height")?.toFloat() ?: fallbackH
        val predictions = root.getJSONArray("predictions")
        val result      = mutableListOf<LinePrediction>()

        for (i in 0 until predictions.length()) {
            val pred         = predictions.getJSONObject(i)
            val className    = pred.getString("class")
            val confidence   = pred.getDouble("confidence").toFloat()
            val keypointsArr = pred.optJSONArray("keypoints") ?: continue
            val points       = mutableListOf<Pair<Float, Float>>()

            for (j in 0 until keypointsArr.length()) {
                val pt = keypointsArr.getJSONObject(j)
                points.add(
                    Pair(
                        pt.getDouble("x").toFloat() / imgW,
                        pt.getDouble("y").toFloat() / imgH
                    )
                )
            }

            if (points.isNotEmpty()) {
                result.add(LinePrediction(className, confidence, points))
            }
        }

        return result
    }
}
