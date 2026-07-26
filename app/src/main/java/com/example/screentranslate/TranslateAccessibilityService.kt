package com.example.screentranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 无障碍点译服务（推荐模式）。
 *
 * 原理：通过无障碍接口直接读取当前界面（如 Maya）的文字节点树，连同每段文字在
 * 屏幕上的坐标（boundsInScreen）一起拿到，再交给本地离线翻译引擎译成中文，
 * 直接「盖」在原文所在位置上——效果接近浏览器网页翻译的原地替换。
 *
 * 关键点：它读的是「文字内容 + 坐标」，不是「截屏像素」，所以即使 Maya 设置了
 * 防截屏（FLAG_SECURE），这里依然能拿到文字并翻译——这正是普通截屏类翻译软件
 * 翻不了 Maya 全屏的根因，本服务从机制上绕开了它。
 *
 * 用法：服务开启后屏幕出现蓝色「译」悬浮球（可拖动）。点它 → 当前界面英文原地
 * 变中文；再点一次「译」球或右上角「✕」关闭译层。
 */
class TranslateAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var ball: View? = null
    private var overlay: FrameLayout? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 预热离线模型（若已下载则秒就绪）
        Translator.ensureModel { }
        showBall()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 手动触发，避免频繁翻译。
    }

    override fun onInterrupt() {}

    // ── 悬浮球（可拖动，点按翻译）──────────────────────────────
    private fun showBall() {
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 600

        val b = TextView(this)
        b.text = "译"
        b.setPadding(40, 30, 40, 30)
        b.setBackgroundColor(0xFF1565C0.toInt())
        b.setTextColor(Color.WHITE)
        b.textSize = 18f
        b.setTypeface(b.typeface, Typeface.BOLD)

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try { wm.updateViewLayout(b, params) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleTranslate()
                }
            }
            true
        }
        try { wm.addView(b, params); ball = b } catch (e: Exception) { e.printStackTrace() }
    }

    private fun toggleTranslate() {
        if (overlay != null) { clearOverlay(); return }
        translateScreen()
    }

    // ── 读屏 + 翻译 + 原地叠加 ────────────────────────────────
    private fun translateScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            toast("无法读取当前界面，请切到 Maya 等目标 App 再点")
            return
        }
        val items = ArrayList<Pair<String, Rect>>()
        collect(root, items)
        if (items.isEmpty()) {
            toast("当前界面没有可翻译的英文")
            return
        }
        showOverlay(items)
    }

    /** 递归收集可见、可翻译的文字节点，连同其屏幕坐标。 */
    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<Pair<String, Rect>>) {
        node ?: return
        if (out.size >= MAX_NODES) return
        if (node.isVisibleToUser) {
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (isTranslatable(raw)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) out.add(raw to r)
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    /** 过滤：太短/太长、无英文字母、已是中文的一律跳过，省时省乱。 */
    private fun isTranslatable(s: String): Boolean {
        if (s.length < 2 || s.length > 200) return false
        val letters = s.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (letters < 2) return false
        val han = s.count { it.code in 0x4E00..0x9FFF }
        return han <= letters
    }

    private fun showOverlay(items: List<Pair<String, Rect>>) {
        main.post {
            clearOverlay()
            val container = FrameLayout(this)
            container.setBackgroundColor(0x33000000) // 轻微压暗，突出译文
            // 点空白处关闭
            container.setOnClickListener { clearOverlay() }

            // 每段原文位置放一个中文译文片
            val chips = ArrayList<TextView>(items.size)
            for ((_, box) in items) {
                val chip = TextView(this)
                chip.text = "…"
                chip.setTextColor(Color.WHITE)
                chip.setBackgroundColor(0xF01565C0.toInt())
                chip.textSize = 13f
                chip.setPadding(10, 6, 10, 6)
                chip.maxWidth = maxOf(box.width(), (resources.displayMetrics.density * 80).toInt())
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = box.left
                lp.topMargin = box.top
                chip.layoutParams = lp
                chip.setOnClickListener { clearOverlay() }
                container.addView(chip)
                chips.add(chip)
            }

            // 整屏一次批量翻译（离线 ML Kit / 有道 / 谷歌 / 本地 GLM 自动级联），
            // 相比逐条请求快数倍，且带缓存——再次打开同一界面秒出。
            val srcs = items.map { it.first }
            Translator.translateBatch(srcs) { zhList ->
                main.post {
                    chips.forEachIndexed { i, chip ->
                        val src = srcs[i]
                        val zh = zhList.getOrNull(i).orEmpty()
                        chip.text = if (zh.isBlank() || zh == src) src else zh
                    }
                }
            }

            // 右上角关闭按钮
            val close = TextView(this)
            close.text = "✕ 关闭译层"
            close.setTextColor(Color.WHITE)
            close.setBackgroundColor(0xCC000000.toInt())
            close.setPadding(28, 16, 28, 16)
            close.textSize = 14f
            val clp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clp.gravity = Gravity.TOP or Gravity.END
            clp.topMargin = 40
            clp.rightMargin = 24
            close.layoutParams = clp
            close.setOnClickListener { clearOverlay() }
            container.addView(close)

            val params = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try {
                wm.addView(container, params)
                overlay = container
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun clearOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        clearOverlay()
        ball?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    companion object {
        private const val MAX_NODES = 80
    }
}
