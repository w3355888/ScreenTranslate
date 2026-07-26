package com.example.screentranslate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView

    // 申请「截屏」（MediaProjection）权限，拿到结果后启动悬浮窗服务
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(intent)
            Toast.makeText(this, "悬浮球已启动，点它翻译当前屏幕", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "需要截屏权限才能翻译", Toast.LENGTH_SHORT).show()
        }
    }

    // 申请「悬浮窗」权限
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { requestCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        updateStatus()

        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.btnShot).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayLauncher.launch(intent)
            } else {
                requestCapture()
            }
        }

        findViewById<Button>(R.id.btnModel).setOnClickListener {
            Toast.makeText(this, "正在下载离线翻译模型…", Toast.LENGTH_SHORT).show()
            Translator.ensureModel { ok ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "离线模型已就绪，可离线翻译" else "下载失败：请挂菲律宾节点 VPN 后重试，或在 Translator.kt 配置本地 GLM",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val acc = if (isAccessibilityEnabled()) "✅ 无障碍点译：已开启" else "⚪ 无障碍点译：未开启"
        txtStatus.text = acc
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/.TranslateAccessibilityService"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(service, ignoreCase = true) == true
                || enabled?.contains(packageName, ignoreCase = true) == true
    }

    private fun openAccessibilitySettings() {
        if (isAccessibilityEnabled()) {
            Toast.makeText(this, "无障碍点译已开启，去 Maya 点屏幕上蓝色「译」球即可", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "在设置里找到「屏幕翻译 / ScreenTranslate」并开启", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }
}
