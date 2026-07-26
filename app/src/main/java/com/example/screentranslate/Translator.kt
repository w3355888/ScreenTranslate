package com.example.screentranslate

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 翻译模块 v1.3 —— 多引擎级联，谁能用走谁，永不"点了没反应"。
 *
 * 引擎优先级（自动降级，失败进入 60 秒冷却后再重试）：
 *   1) ML Kit 离线模型（英→中）  —— 无网可用，最快；需手机有 Google 服务(GMS)
 *   2) 有道免费网页接口          —— 国内直连秒通，无需 Key（模型没下好/无 GMS 时的主力）
 *   3) 谷歌免费网页接口          —— 质量好，但需要能访问谷歌的网络
 *   4) 本地 GLM（llama-server）  —— 手机与电脑同 WiFi 时可用，完全私有
 *   5) 原文兜底                  —— 以上全挂时返回原文，不卡死界面
 *
 * 其它优化：
 *   - 批量翻译 translateBatch()：整屏几十段文字合并成 1 次在线请求（原来几十次），速度提升数倍
 *   - LRU 缓存：同一句只翻一次，重复打开秒出
 */
object Translator {

    private const val TAG = "Translator"

    // ── 本地 GLM 配置（可选）──────────────────────────────────────────
    // 例：LOCAL_ENDPOINT = "http://192.168.1.5:8080/v1/chat/completions"
    // 需确保 llama-server 监听 0.0.0.0 且电脑防火墙放行该端口，手机连同一 WiFi。
    private const val LOCAL_ENDPOINT: String = ""
    private const val LOCAL_API_KEY: String = "not-needed"

    // ── ML Kit 离线引擎 ───────────────────────────────────────────────
    private val mlOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.CHINESE)
        .build()
    private val mlTranslator = Translation.getClient(mlOptions)

    @Volatile private var mlReady = false
    @Volatile private var mlFailed = false
    @Volatile private var downloading = false

    // ── 在线引擎失败冷却（避免反复撞死接口）───────────────────────────
    private const val COOLDOWN_MS = 60_000L
    @Volatile private var youdaoFailedAt = 0L
    @Volatile private var googleFailedAt = 0L
    private fun engineUp(failedAt: Long) = System.currentTimeMillis() - failedAt > COOLDOWN_MS

    // ── 翻译缓存（LRU，最多 600 条）──────────────────────────────────
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }

    /**
     * 确保离线模型已下载（约 30MB，下载需能访问 Google；下载完成后永久离线可用）。
     * 即使没下载成功也不影响使用——会自动走有道/谷歌在线翻译。
     */
    fun ensureModel(onDone: (Boolean) -> Unit) {
        if (mlReady) { onDone(true); return }
        if (downloading) { onDone(false); return }
        downloading = true
        val conditions = DownloadConditions.Builder().build()
        mlTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { mlReady = true; mlFailed = false; downloading = false; onDone(true) }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML model download failed: ${e.message}")
                mlFailed = true; downloading = false
                onDone(false)
            }
    }

    /** 单条翻译（FloatService 等旧调用兼容）。 */
    fun translate(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) { onResult(""); return }
        translateBatch(listOf(text)) { onResult(it.firstOrNull() ?: text) }
    }

    /**
     * 批量翻译：结果列表与输入一一对应（顺序一致）。
     * 回调可能在任意线程触发，UI 侧请自行 post 回主线程。
     */
    fun translateBatch(texts: List<String>, onResult: (List<String>) -> Unit) {
        if (texts.isEmpty()) { onResult(emptyList()); return }
        val results = arrayOfNulls<String>(texts.size)
        val pendingIdx = ArrayList<Int>()
        synchronized(cache) {
            texts.forEachIndexed { i, t ->
                val hit = cache[t]
                if (hit != null) results[i] = hit else pendingIdx.add(i)
            }
        }
        if (pendingIdx.isEmpty()) {
            onResult(List(texts.size) { results[it] ?: texts[it] })
            return
        }
        val pendingTexts = pendingIdx.map { texts[it] }

        fun finish(translated: List<String>) {
            synchronized(cache) {
                pendingIdx.forEachIndexed { k, i ->
                    val zh = translated.getOrNull(k)?.takeIf { it.isNotBlank() } ?: texts[i]
                    results[i] = zh
                    if (zh != texts[i]) cache[texts[i]] = zh
                }
            }
            onResult(List(texts.size) { results[it] ?: texts[it] })
        }

        if (mlReady) {
            mlBatch(pendingTexts) { mlOut ->
                // ML Kit 个别条目失败时，把失败的再送在线引擎补翻
                val missIdx = mlOut.indices.filter { mlOut[it] == null }
                if (missIdx.isEmpty()) {
                    finish(mlOut.map { it!! })
                } else {
                    thread {
                        val patched = onlineBatch(missIdx.map { pendingTexts[it] })
                        val merged = mlOut.toMutableList()
                        missIdx.forEachIndexed { k, i -> merged[i] = patched.getOrNull(k) ?: pendingTexts[i] }
                        finish(merged.map { it ?: "" })
                    }
                }
            }
            return
        }
        // 模型没就绪：后台自动下载一次（不阻塞本次翻译，本次直接走在线引擎）
        if (!mlFailed && !downloading) ensureModel { }
        thread { finish(onlineBatch(pendingTexts)) }
    }

    // ── ML Kit 批量（并发，计数归零后回调；失败条目为 null）──────────
    private fun mlBatch(texts: List<String>, onDone: (List<String?>) -> Unit) {
        val out = arrayOfNulls<String>(texts.size)
        val remaining = AtomicInteger(texts.size)
        texts.forEachIndexed { i, t ->
            mlTranslator.translate(t)
                .addOnSuccessListener { zh ->
                    out[i] = zh
                    if (remaining.decrementAndGet() == 0) onDone(out.toList())
                }
                .addOnFailureListener {
                    out[i] = null
                    if (remaining.decrementAndGet() == 0) onDone(out.toList())
                }
        }
    }

    // ── 在线引擎级联：有道 → 谷歌 → 本地 GLM → 原文 ──────────────────
    private fun onlineBatch(texts: List<String>): List<String> {
        if (engineUp(youdaoFailedAt)) {
            try { return youdaoBatch(texts) } catch (e: Exception) {
                Log.w(TAG, "youdao failed: ${e.message}")
                youdaoFailedAt = System.currentTimeMillis()
            }
        }
        if (engineUp(googleFailedAt)) {
            try { return googleBatch(texts) } catch (e: Exception) {
                Log.w(TAG, "google failed: ${e.message}")
                googleFailedAt = System.currentTimeMillis()
            }
        }
        if (LOCAL_ENDPOINT.isNotBlank()) {
            try { return localGlmBatch(texts) } catch (e: Exception) {
                Log.e(TAG, "local glm failed: ${e.message}")
            }
        }
        return texts // 全挂：原文兜底
    }

    /**
     * 有道免费网页接口（国内直连、无需 Key）。
     * 按 \n 分行批量：translateResult 每行一个数组。
     */
    private fun youdaoBatch(texts: List<String>): List<String> {
        val joined = texts.joinToString("\n") { it.replace('\n', ' ') }
        val url = "https://fanyi.youdao.com/translate?&doctype=json&type=EN2ZH_CN&i=" +
                URLEncoder.encode(joined, "UTF-8")
        val body = httpGet(url)
        val obj = JSONObject(body)
        if (obj.optInt("errorCode", -1) != 0) throw RuntimeException("youdao errorCode=${obj.opt("errorCode")}")
        val arr = obj.getJSONArray("translateResult")
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val line = arr.getJSONArray(i)
            val sb = StringBuilder()
            for (j in 0 until line.length()) sb.append(line.getJSONObject(j).optString("tgt"))
            out.add(sb.toString().trim())
        }
        return align(out, texts)
    }

    /**
     * 谷歌免费网页接口（translate.googleapis.com，需能访问谷歌）。
     * 合并所有分段后按 \n 还原行。
     */
    private fun googleBatch(texts: List<String>): List<String> {
        val joined = texts.joinToString("\n") { it.replace('\n', ' ') }
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t&q=" +
                URLEncoder.encode(joined, "UTF-8")
        val body = httpGet(url)
        val root = JSONArray(body)
        val segs = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.getJSONArray(i)
            if (!seg.isNull(0)) sb.append(seg.getString(0))
        }
        val lines = sb.toString().split("\n").map { it.trim() }
        return align(lines, texts)
    }

    /** 行数对不上时兜底：缺的行用原文补齐。 */
    private fun align(out: List<String>, texts: List<String>): List<String> =
        List(texts.size) { i -> out.getOrNull(i)?.takeIf { it.isNotBlank() } ?: texts[i] }

    // ── 本地 GLM / llama-server（OpenAI 兼容）─────────────────────────
    private fun localGlmBatch(texts: List<String>): List<String> {
        val joined = texts.joinToString("\n") { it.replace('\n', ' ') }
        val body = """
            {
              "model": "local",
              "messages": [
                {"role":"system","content":"You are a translator. Translate each line of the user text from English to Simplified Chinese. Output exactly one translated line per input line, same order, no numbering, no explanation."},
                {"role":"user","content":${quote(joined)}}
              ],
              "temperature": 0
            }
        """.trimIndent()
        val conn = URL(LOCAL_ENDPOINT).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $LOCAL_API_KEY")
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
        conn.outputStream.flush()
        conn.inputStream.bufferedReader().use { reader ->
            val json = reader.readText()
            val obj = JSONObject(json)
            val content = obj.getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()
            return align(content.split("\n").map { it.trim() }, texts)
        }
    }

    // ── HTTP 工具 ─────────────────────────────────────────────────────
    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        )
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("HTTP $code")
        conn.inputStream.bufferedReader().use { return it.readText() }
    }

    private fun quote(s: String): String =
        JSONObject().put("x", s).toString().let { it.substring(9, it.length - 1) }
}
