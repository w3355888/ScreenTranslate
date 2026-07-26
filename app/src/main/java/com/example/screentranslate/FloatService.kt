package com.example.screentranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FloatService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null

    private var ballView: View? = null
    private var resultView: View? = null
    private val capturing = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startForeground(NOTIFY_ID, buildNotification())
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            resultCode = it.getIntExtra("resultCode", 0)
            @Suppress("DEPRECATION")
            resultData = it.getParcelableExtra("data")
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val ch = "screen_translate_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(ch, "屏幕翻译", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("屏幕翻译运行中")
            .setContentText("点悬浮球翻译当前屏幕")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun showBall() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        val ball = ImageView(this)
        ball.setBackgroundResource(android.R.color.holo_blue_dark)
        ball.setPadding(34, 34, 34, 34)
        ball.textAlignment = View.TEXT_ALIGNMENT_CENTER
        // 用一个小方块代替图标，避免依赖外部资源
        ball.setImageResource(android.R.drawable.ic_menu_view)

        var lastX = 0
        var lastY = 0
        var downX = 0f
        var downY = 0f
        ball.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = lastX + (event.rawX - downX).toInt()
                    params.y = lastY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(ball, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(event.rawX - downX) < 6 && Math.abs(event.rawY - downY) < 6) {
                        captureAndTranslate()
                    }
                }
            }
            true
        }
        windowManager.addView(ball, params)
        ballView = ball
    }

    private fun captureAndTranslate() {
        if (capturing.get()) return
        if (resultData == null) {
            Toast.makeText(this, "截屏权限未授予，请重开 App", Toast.LENGTH_SHORT).show()
            return
        }
        capturing.set(true)
        if (mediaProjection == null) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData!!)
        }
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val d = metrics.densityDpi
        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val vd: VirtualDisplay = mediaProjection!!.createVirtualDisplay(
            "Capture", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                vd.release()
                runOcr(bitmap)
            } else {
                vd.release()
                capturing.set(false)
                Toast.makeText(this, "截屏失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }, 350)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun runOcr(bitmap: Bitmap) {
        val client = TextRecognition.getClient()
        val input = InputImage.fromBitmap(bitmap, 0)
        client.process(input).addOnSuccessListener { visionText ->
            val blocks = visionText.textBlocks
            if (blocks.isEmpty()) {
                capturing.set(false)
                showResult(bitmap, LinkedHashMap())
                return@addOnSuccessListener
            }
            val map = LinkedHashMap<Rect, String>()
            val counter = AtomicInteger(blocks.size)
            for (block in blocks) {
                val txt = block.text
                val box = block.boundingBox ?: Rect(0, 0, 1, 1)
                Translator.translate(txt) { zh ->
                    synchronized(map) {
                        map[box] = zh
                        if (counter.decrementAndGet() == 0) {
                            showResult(bitmap, map)
                            capturing.set(false)
                        }
                    }
                }
            }
        }.addOnFailureListener {
            capturing.set(false)
            showResult(bitmap, LinkedHashMap())
            Toast.makeText(this, "识别失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResult(original: Bitmap, translations: Map<Rect, String>) {
        Handler(Looper.getMainLooper()).post {
            val out = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val bg = Paint().apply { color = Color.BLACK; alpha = 215 }
            val tp = Paint().apply {
                color = Color.WHITE
                textSize = resources.displayMetrics.density * 15
            }
            for ((box, zh) in translations) {
                canvas.drawRect(box, bg)
                val lines = zh.split("\n")
                var y = box.top + tp.textSize
                for (line in lines) {
                    canvas.drawText(line, box.left + 4f, y, tp)
                    y += tp.textSize * 1.15f
                }
            }

            resultView?.let { windowManager.removeView(it) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            val frame = FrameLayout(this)
            val iv = ImageView(this)
            iv.setImageBitmap(out)
            iv.scaleType = ImageView.ScaleType.FIT_START
            frame.addView(iv)

            val close = TextView(this)
            close.text = "✕ 关闭"
            close.setBackgroundColor(Color.argb(190, 0, 0, 0))
            close.setTextColor(Color.WHITE)
            close.setPadding(50, 24, 50, 24)
            close.setOnClickListener {
                resultView?.let { v -> windowManager.removeView(v) }
                resultView = null
            }
            val closeParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.END }
            frame.addView(close, closeParams)

            windowManager.addView(frame, params)
            resultView = frame
        }
    }

    override fun onDestroy() {
        ballView?.let { windowManager.removeView(it) }
        resultView?.let { windowManager.removeView(it) }
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFY_ID = 1001
    }
}
