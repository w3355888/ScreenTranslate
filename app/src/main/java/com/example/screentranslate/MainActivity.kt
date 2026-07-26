package com.example.screentranslate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        updateStatus()

        // 开启无障碍点译（核心：能翻 Maya 全屏，绕过防截屏）
        findViewById<Button>(R.id.btnAcc).setOnClickListener {
            openAccessibilitySettings()
        }

        // 下载/校验离线翻译模型（首次联网一次，之后完全离线）
        findViewById<Button>(R.id.btnModel).setOnClickListener {
            Toast.makeText(this, "正在下载离线翻译模型…", Toast.LENGTH_SHORT).show()
            Translator.ensureModel { ok ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "离线模型已就绪，可离线翻译" else "下载失败：请联网后重试，或在 Translator.kt 配置本地 GLM",
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
        val acc = if (isAccessibilityEnabled()) "✅ 无障碍点译：已开启" else "⚪ 无障碍点译：未开启（点上方按钮去开启）"
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
}
