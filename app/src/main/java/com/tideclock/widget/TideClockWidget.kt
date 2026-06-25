package com.tideclock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.os.IBinder
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class TideClockWidget : AppWidgetProvider() {
    
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.tideclock.widget.REFRESH"
        
        internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            
            // Start service to fetch data
            val intent = Intent(context, TideClockService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }
    }
}

class TideClockService : LifecycleService() {
    
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) 
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        
        lifecycleScope.launch {
            try {
                val widgetBitmap = withContext(Dispatchers.IO) { fetchDataAndRenderWidget() }
                if (widgetBitmap != null && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val appWidgetManager = AppWidgetManager.getInstance(this@TideClockService)
                    val views = RemoteViews(packageName, R.layout.widget_layout)
                    views.setImageViewBitmap(R.id.widget_image, widgetBitmap)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            stopSelf()
        }
        
        return super.onStartCommand(intent, flags, startId)
    }
    
    private fun fetchDataAndRenderWidget(): Bitmap? {
        return try {
            // Get location (mock for now - in real app would use FusedLocationProvider)
            val lat = 28.37 // Tenerife
            val lon = -16.71
            
            // Fetch tide data from TideTurtle
            val tideData = fetchTideData(lat, lon)
            val waveData = fetchWaveData(lat, lon)
            
            // Render widget
            renderWidget(tideData, waveData)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun fetchTideData(lat: Double, lon: Double): JSONObject? {
        val url = URL("https://tideturtle.com/api/v1/tides?lat=${lat}&lon=${lon}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        
        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response).optJSONObject("tides")?.optJSONObject("data")
    }
    
    private fun fetchWaveData(lat: Double, lon: Double): JSONObject? {
        val url = URL("https://marine-api.open-meteo.com/v1/marine?latitude=${lat}&longitude=${lon}&hourly=wave_height,wave_period&timezone=auto")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        
        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response).optJSONObject("hourly")
    }
    
    private fun renderWidget(tideData: JSONObject?, waveData: JSONObject?): Bitmap {
        val width = 400
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = width * 0.42f
        
        // Background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#0a1628")
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)
        
        // Clock face gradient
        val facePaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(cx, cy - radius, cx, cy + radius, 
                Color.parseColor("#1a3a5c"), Color.parseColor("#071320"), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, radius * 0.95f, facePaint)
        
        // Border
        val borderPaint = Paint().apply {
            color = Color.parseColor("#2a4a6a")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)
        
        // Water fill (simulate with tide height)
        val extrema = tideData?.optJSONArray("extrema")
        var waterLevel = 0.5f
        if (extrema != null && extrema.length() > 0) {
            val heights = mutableListOf<Double>()
            for (i in 0 until extrema.length()) {
                extrema.optJSONObject(i)?.optDouble("height")?.let { heights.add(it) }
            }
            if (heights.isNotEmpty()) {
                val min = heights.minOrNull() ?: 0.0
                val max = heights.maxOrNull() ?: 1.0
                val current = heights.getOrNull(0) ?: 0.0
                waterLevel = ((current - min) / (max - min)).toFloat().coerceIn(0.1f, 0.9f)
            }
        }
        
        val waterHeight = radius * 1.8f * waterLevel
        val waterY = cy + radius - waterHeight
        
        val waterPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(cx, waterY, cx, cy + radius,
                Color.parseColor("#0096c8"), Color.parseColor("#003060"), Shader.TileMode.CLAMP)
        }
        
        val waterPath = Path().apply {
            addArc(RectF(cx - radius, waterY - 30, cx + radius, cy + radius + 50), 180f, 180f)
            lineTo(cx - radius, cy + radius)
            lineTo(cx + radius, cy + radius)
            close()
        }
        canvas.drawPath(waterPath, waterPaint)
        
        // Wave on water surface
        val wavePaint = Paint().apply {
            color = Color.parseColor("#00b4dc")
            alpha = 100
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        
        val wavePath = Path()
        wavePath.moveTo(cx - radius, waterY)
        for (i in 0..20) {
            val x = cx - radius + (radius * 2 / 20) * i
            val y = waterY + Math.sin((i * 0.5).toDouble()).toFloat() * 8
            wavePath.lineTo(x, y)
        }
        canvas.drawPath(wavePath, wavePaint)
        
        // Clock markers
        val markerPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        
        for (i in 0..59) {
            val angle = Math.toRadians((i * 6 - 90).toDouble())
            val isHour = i % 5 == 0
            val innerR = radius * 0.82f
            val outerR = if (isHour) radius * 0.92f else radius * 0.87f
            
            val x1 = cx + Math.cos(angle).toFloat() * innerR
            val y1 = cy + Math.sin(angle).toFloat() * innerR
            val x2 = cx + Math.cos(angle).toFloat() * outerR
            val y2 = cy + Math.sin(angle).toFloat() * outerR
            
            markerPaint.strokeWidth = if (isHour) 5f else 2f
            markerPaint.alpha = if (isHour) 255 else 128
            canvas.drawLine(x1, y1, x2, y2, markerPaint)
        }
        
        // Hour numbers
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = radius * 0.14f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        val numbers = listOf("12", "3", "6", "9")
        val textPositions = listOf(0f, 90f, 180f, 270f)
        for ((i, angle) in textPositions.withIndex()) {
            val rad = Math.toRadians(angle - 90.0)
            val textRadius = radius * 0.68f
            val tx = cx + Math.cos(rad).toFloat() * textRadius
            val ty = cy + Math.sin(rad).toFloat() * textRadius + textPaint.textSize / 3
            canvas.drawText(numbers[i], tx, ty, textPaint)
        }
        
        // Clock hands
        val now = Calendar.getInstance()
        val hours = now.get(Calendar.HOUR)
        val minutes = now.get(Calendar.MINUTE)
        val seconds = now.get(Calendar.SECOND)
        
        // Hour hand
        val hourAngle = Math.toRadians(((hours + minutes / 60f) * 30 - 90).toDouble())
        val hourPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawLine(cx, cy, 
            cx + Math.cos(hourAngle).toFloat() * radius * 0.45f,
            cy + Math.sin(hourAngle).toFloat() * radius * 0.45f, hourPaint)
        
        // Minute hand
        val minuteAngle = Math.toRadians((minutes + seconds / 60f) * 6 - 90)
        val minutePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawLine(cx, cy,
            cx + Math.cos(minuteAngle).toFloat() * radius * 0.65f,
            cy + Math.sin(minuteAngle).toFloat() * radius * 0.65f, minutePaint)
        
        // Second hand
        val secondAngle = Math.toRadians(seconds * 6 - 90)
        val secondPaint = Paint().apply {
            color = Color.parseColor("#00b4dc")
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawLine(cx, cy,
            cx + Math.cos(secondAngle).toFloat() * radius * 0.7f,
            cy + Math.sin(secondAngle).toFloat() * radius * 0.7f, secondPaint)
        
        // Center dot
        val centerPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, 12f, centerPaint)
        
        val centerInner = Paint().apply {
            color = Color.parseColor("#00b4dc")
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, 7f, centerInner)
        
        // Time display
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeText = timeFormat.format(now.time)
        val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        val dateText = dateFormat.format(now.time)
        
        val displayPaint = Paint().apply {
            color = Color.WHITE
            textSize = radius * 0.28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(timeText, cx, cy + radius * 0.15f, displayPaint)
        
        displayPaint.textSize = radius * 0.1f
        displayPaint.alpha = 180
        canvas.drawText(dateText, cx, cy + radius * 0.28f, displayPaint)
        
        // Wave data
        val waveHeight = waveData?.optJSONArray("wave_height")?.optDouble(0) ?: 0.7
        val wavePeriod = waveData?.optJSONArray("wave_period")?.optDouble(0) ?: 6.8
        
        val waveText = String.format("%.1fft", waveHeight * 3.28084)
        val infoPaint = Paint().apply {
            color = Color.parseColor("#00b4dc")
            textSize = radius * 0.12f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Waves: $waveText", cx, cy + radius * 0.95f, infoPaint)
        
        return bitmap
    }
}
