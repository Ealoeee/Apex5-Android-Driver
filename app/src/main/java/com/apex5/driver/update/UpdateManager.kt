package com.apex5.driver.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.apex5.driver.BuildConfig
import com.apex5.driver.service.DriverController
import com.apex5.driver.shizuku.ShizukuBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionClean: String,
    val title: String,
    val releaseNotes: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val publishedAt: String
)

enum class UpdateStage {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING_SHIZUKU,
    ERROR
}

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val latestRelease: ReleaseInfo? = null,
    val downloadedFilePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val useMirrorProxy: Boolean = false,
    val statusMessage: String = "点击检查是否有新版本发布",
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object UpdateManager {
    private const val GITHUB_API_LATEST =
        "https://api.github.com/repos/Ealoeee/Apex5-Android-Driver/releases/latest"
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var busy = false

    fun setUseMirrorProxy(enabled: Boolean) {
        _state.update { it.copy(useMirrorProxy = enabled) }
    }

    fun checkForUpdate(silent: Boolean = false) {
        if (busy) return
        busy = true
        _state.update {
            it.copy(
                stage = UpdateStage.CHECKING,
                errorMessage = null,
                statusMessage = "正在从 GitHub 查询最新版本..."
            )
        }

        Thread({
            try {
                val jsonText = httpGetText(GITHUB_API_LATEST)
                val obj = JSONObject(jsonText)
                val tag = obj.optString("tag_name", "").trim()
                val title = obj.optString("name", tag).ifBlank { tag }
                val body = obj.optString("body", "").trim()
                val publishedAt = obj.optString("published_at", "").take(10)
                val assets = obj.optJSONArray("assets")

                var apkUrl = ""
                var apkName = ""
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkName = name
                            apkUrl = a.optString("browser_download_url", "")
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }

                val remoteClean = normalizeVersion(tag)
                val localClean = normalizeVersion(BuildConfig.VERSION_NAME)

                if (tag.isBlank() || apkUrl.isBlank()) {
                    _state.update {
                        it.copy(
                            stage = UpdateStage.UP_TO_DATE,
                            statusMessage = "当前版本 v$localClean 已是最新（未找到可下载 APK）"
                        )
                    }
                    return@Thread
                }

                val info = ReleaseInfo(
                    tagName = tag,
                    versionClean = remoteClean,
                    title = title,
                    releaseNotes = body,
                    apkName = apkName,
                    downloadUrl = apkUrl,
                    sizeBytes = apkSize,
                    publishedAt = publishedAt
                )

                if (isNewerVersion(remoteClean, localClean)) {
                    _state.update {
                        it.copy(
                            stage = UpdateStage.UPDATE_AVAILABLE,
                            latestRelease = info,
                            downloadedFilePath = null,
                            statusMessage = "发现新版本 $tag（当前 v$localClean）"
                        )
                    }
                    DriverController.appendLog("发现新版本: $tag ($apkName)")
                } else {
                    _state.update {
                        it.copy(
                            stage = UpdateStage.UP_TO_DATE,
                            latestRelease = info,
                            statusMessage = "当前已是最新版本 v$localClean (GitHub 最新: $tag)"
                        )
                    }
                    if (!silent) {
                        DriverController.appendLog("检查更新：当前 v$localClean 已是最新版本")
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "网络请求失败"
                _state.update {
                    it.copy(
                        stage = UpdateStage.ERROR,
                        errorMessage = "检查更新失败: $msg",
                        statusMessage = "检查更新失败"
                    )
                }
                if (!silent) {
                    DriverController.appendLog("检查更新失败: $msg")
                }
            } finally {
                busy = false
            }
        }, "Apex5-UpdateCheck").start()
    }

    fun downloadLatestApk(context: Context) {
        val release = _state.value.latestRelease ?: return
        if (busy) return
        busy = true

        val useMirror = _state.value.useMirrorProxy
        val targetUrl = if (useMirror) {
            "$MIRROR_PREFIX${release.downloadUrl}"
        } else {
            release.downloadUrl
        }

        _state.update {
            it.copy(
                stage = UpdateStage.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = release.sizeBytes,
                errorMessage = null,
                statusMessage = "正在下载 ${release.apkName}${if (useMirror) " (镜像加速)" else ""}..."
            )
        }

        val updatesDir = File(context.applicationContext.cacheDir, "updates").apply { mkdirs() }
        // Clean stale APKs before downloading
        updatesDir.listFiles()?.forEach { f ->
            if (f.name != release.apkName) f.delete()
        }
        val finalFile = File(updatesDir, release.apkName)
        val tmpFile = File(updatesDir, "${release.apkName}.tmp")

        Thread({
            try {
                val conn = openFollowedConnection(targetUrl)
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else release.sizeBytes
                _state.update { it.copy(totalBytes = total) }

                conn.inputStream.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var received = 0L
                        var lastUiUpdate = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            received += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 120L) {
                                lastUiUpdate = now
                                _state.update {
                                    it.copy(
                                        downloadedBytes = received,
                                        totalBytes = total,
                                        statusMessage = "正在下载 ${formatMb(received)} / ${formatMb(total)}"
                                    )
                                }
                            }
                        }
                    }
                }
                if (finalFile.exists()) finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                _state.update {
                    it.copy(
                        stage = UpdateStage.READY_TO_INSTALL,
                        downloadedFilePath = finalFile.absolutePath,
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length(),
                        statusMessage = "下载完成 (${formatMb(finalFile.length())})，可以开始安装"
                    )
                }
                DriverController.appendLog("更新包下载完成: ${finalFile.name}")
            } catch (e: Exception) {
                tmpFile.delete()
                val msg = e.message ?: "下载中断"
                _state.update {
                    it.copy(
                        stage = UpdateStage.ERROR,
                        errorMessage = "下载失败: $msg（可尝试切换镜像加速开关后重试）",
                        statusMessage = "下载失败"
                    )
                }
                DriverController.appendLog("下载更新失败: $msg")
            } finally {
                busy = false
            }
        }, "Apex5-ApkDownload").start()
    }

    fun installViaSystemInstaller(context: Context) {
        val path = _state.value.downloadedFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            _state.update { it.copy(errorMessage = "安装包已清理，请重新下载") }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            _state.update { it.copy(statusMessage = "请先在系统设置中允许「安装未知应用」，返回后再次点击安装") }
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    fun installViaShizuku() {
        val path = _state.value.downloadedFilePath ?: return
        val file = File(path)
        if (!file.exists() || busy) return
        busy = true
        _state.update {
            it.copy(
                stage = UpdateStage.INSTALLING_SHIZUKU,
                errorMessage = null,
                statusMessage = "正在通过 Shizuku (shell) 覆盖安装..."
            )
        }

        Thread({
            try {
                val res = ShizukuBridge.installApkViaShizuku(file)
                res.onSuccess { msg ->
                    if (msg.startsWith("SUCCESS")) {
                        _state.update {
                            it.copy(
                                stage = UpdateStage.READY_TO_INSTALL,
                                statusMessage = "Shizuku 安装完成，请重新打开应用"
                            )
                        }
                    } else {
                        val hint = if (msg.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)) {
                            "签名不一致（v0.3.0 及更早版本使用了临时 CI 密钥）。请先手动卸载旧版一次再安装 v0.4.0，此后版本即可直接覆盖更新！"
                        } else msg
                        _state.update {
                            it.copy(
                                stage = UpdateStage.READY_TO_INSTALL,
                                errorMessage = hint,
                                statusMessage = "Shizuku 安装未成功，可改用系统安装器"
                            )
                        }
                    }
                    DriverController.appendLog("Shizuku 安装结果: $msg")
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            stage = UpdateStage.READY_TO_INSTALL,
                            errorMessage = "Shizuku 调用失败: ${err.message}",
                            statusMessage = "请改用系统安装器安装"
                        )
                    }
                }
            } finally {
                busy = false
            }
        }, "Apex5-ShizukuInstall").start()
    }

    private fun httpGetText(urlStr: String): String {
        val conn = openFollowedConnection(urlStr)
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun openFollowedConnection(urlStr: String): HttpURLConnection {
        var current = urlStr
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Apex5-Android-Driver/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = loc
            } else {
                return conn
            }
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore("-")

    internal fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val lParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        val count = maxOf(rParts.size, lParts.size)
        for (i in 0 until count) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        return "%.1f MB".format(bytes / (1024f * 1024f))
    }
}
