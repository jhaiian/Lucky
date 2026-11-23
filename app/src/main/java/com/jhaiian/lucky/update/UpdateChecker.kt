package com.jhaiian.lucky.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.jhaiian.lucky.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

sealed interface UpdateStep {
    data object None : UpdateStep
    data object Checking : UpdateStep
    data object UpToDate : UpdateStep
    data object CheckFailed : UpdateStep
    data object DownloadFailed : UpdateStep
    data class Available(
        val version: String,
        val versionCode: Long,
        val changelog: String,
        val downloadUrl: String?,
        val isBeta: Boolean
    ) : UpdateStep
    data object Downloading : UpdateStep
}

object UpdateChecker {
    const val RELEASES_URL = "https://github.com/jhaiian/Lucky/releases"
    const val CHANGELOG_URL = "https://github.com/jhaiian/Lucky/blob/main/CHANGELOG.md"
    private const val STABLE_URL = "https://raw.githubusercontent.com/jhaiian/Lucky/main/Update/Stable.json"
    private const val BETA_URL = "https://raw.githubusercontent.com/jhaiian/Lucky/main/Update/Beta.json"
    private const val SETTINGS_PREFS = "update_settings"
    private const val STATE_PREFS = "update_state"
    private const val KEY_CHECK_ON_LAUNCH = "check_on_launch"
    private const val KEY_SKIP_ON_METERED = "skip_on_metered"
    private const val KEY_BETA_CHANNEL = "beta_channel"
    private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
    private const val KEY_CACHED_APK_VERSION_CODE = "cached_apk_version_code"
    private const val ARCH_UNIVERSAL = "universal"
    private const val PROGRESS_STEP_BYTES = 65_536L
    private const val SPEED_SAMPLE_MILLIS = 400L
    private const val TIMEOUT_MILLIS = 20_000

    val supported: Boolean get() = !BuildConfig.IS_FOSS

    var checkOnLaunch by mutableStateOf(true)
        private set
    var skipOnMetered by mutableStateOf(true)
        private set
    var betaChannel by mutableStateOf(false)
        private set
    var step by mutableStateOf<UpdateStep>(UpdateStep.None)
        private set
    var downloadedBytes by mutableLongStateOf(0L)
        private set
    var totalBytes by mutableLongStateOf(0L)
        private set
    var bytesPerSecond by mutableLongStateOf(0L)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded = false
    private var launchChecked = false
    private var downloadJob: Job? = null

    private fun settings(context: Context) = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private fun state(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = settings(context)
        checkOnLaunch = prefs.getBoolean(KEY_CHECK_ON_LAUNCH, true)
        skipOnMetered = prefs.getBoolean(KEY_SKIP_ON_METERED, true)
        betaChannel = prefs.getBoolean(KEY_BETA_CHANNEL, false)
    }

    fun setCheckOnLaunch(context: Context, value: Boolean) {
        checkOnLaunch = value
        settings(context).edit().putBoolean(KEY_CHECK_ON_LAUNCH, value).apply()
    }

    fun setSkipOnMetered(context: Context, value: Boolean) {
        skipOnMetered = value
        settings(context).edit().putBoolean(KEY_SKIP_ON_METERED, value).apply()
    }

    fun setBetaChannel(context: Context, value: Boolean) {
        betaChannel = value
        settings(context).edit().putBoolean(KEY_BETA_CHANNEL, value).apply()
    }

    fun checkOnLaunch(context: Context) {
        if (!supported || launchChecked) return
        launchChecked = true
        ensureLoaded(context)
        if (!checkOnLaunch) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (skipOnMetered && runCatching { connectivity?.isActiveNetworkMetered == true }.getOrDefault(false)) return
        check(context, silent = true)
    }

    fun check(context: Context, silent: Boolean) {
        if (!supported || step == UpdateStep.Checking || step == UpdateStep.Downloading) return
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        if (!silent) step = UpdateStep.Checking
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchUpdate(appContext) } }
            val available = result.getOrNull()
            if (!silent && step != UpdateStep.Checking) return@launch
            val skipped = state(appContext).getLong(KEY_SKIPPED_VERSION_CODE, -1L)
            step = when {
                result.isFailure -> if (silent) UpdateStep.None else UpdateStep.CheckFailed
                available == null -> if (silent) UpdateStep.None else UpdateStep.UpToDate
                silent && available.versionCode == skipped -> UpdateStep.None
                else -> available
            }
        }
    }

    fun dismiss() {
        if (step != UpdateStep.Downloading) step = UpdateStep.None
    }

    fun skip(context: Context, versionCode: Long) {
        state(context).edit().putLong(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
        step = UpdateStep.None
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun download(context: Context, update: UpdateStep.Available) {
        val appContext = context.applicationContext
        val url = update.downloadUrl ?: return
        val apk = File(appContext.cacheDir, "updates/update.apk")
        val prefs = state(appContext)
        if (apk.length() > 0 && prefs.getLong(KEY_CACHED_APK_VERSION_CODE, -1L) == update.versionCode) {
            step = UpdateStep.None
            install(appContext, apk)
            return
        }
        downloadedBytes = 0L
        totalBytes = 0L
        bytesPerSecond = 0L
        progress = 0f
        step = UpdateStep.Downloading
        downloadJob = scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { downloadTo(url, apk) }
            }.isSuccess
            if (ok) {
                prefs.edit().putLong(KEY_CACHED_APK_VERSION_CODE, update.versionCode).apply()
                step = UpdateStep.None
                install(appContext, apk)
            } else {
                apk.delete()
                step = if (isActive) UpdateStep.DownloadFailed else UpdateStep.None
            }
        }
    }

    private fun fetchUpdate(context: Context): UpdateStep.Available? {
        val stable = JSONObject(fetchText(STABLE_URL))
        val beta = if (betaChannel) runCatching { JSONObject(fetchText(BETA_URL)) }.getOrNull() else null
        val useBeta = beta != null && beta.getLong("versionCode") > stable.getLong("versionCode")
        val json = if (useBeta) beta else stable
        val installed = installedVersionCode(context)
        clearStaleApk(context, installed)
        val remoteCode = json.getLong("versionCode")
        if (remoteCode <= installed) return null
        val downloads = json.optJSONObject("downloads")
        val arch = installedArch(context)
        val url = downloads?.let {
            it.optString(arch).ifEmpty { null } ?: it.optString(ARCH_UNIVERSAL).ifEmpty { null }
        }
        return UpdateStep.Available(
            version = json.getString("version"),
            versionCode = remoteCode,
            changelog = json.optString("changelog").trim(),
            downloadUrl = url,
            isBeta = useBeta
        )
    }

    private fun open(url: String): HttpURLConnection {
        var target = URL(url)
        repeat(5) {
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Lucky")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) error("HTTP $code")
                target = URL(target, location)
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    error("HTTP $code")
                }
                return connection
            }
        }
        error("Too many redirects")
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadTo(url: String, apk: File) {
        apk.parentFile?.mkdirs()
        apk.delete()
        val connection = open(url)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { connection.disconnect() } }
        try {
            val length = connection.contentLengthLong
            withContext(Dispatchers.Main) { totalBytes = length.coerceAtLeast(0L) }
            var done = 0L
            var lastReported = 0L
            var sampleBytes = 0L
            var sampleTime = System.currentTimeMillis()
            var speed = 0L
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = done
                            val now = System.currentTimeMillis()
                            val elapsed = now - sampleTime
                            if (elapsed >= SPEED_SAMPLE_MILLIS) {
                                speed = (done - sampleBytes) * 1000L / elapsed
                                sampleBytes = done
                                sampleTime = now
                            }
                            val reportedSpeed = speed
                            val reportedDone = done
                            withContext(Dispatchers.Main) { report(reportedDone, length, reportedSpeed) }
                        }
                    }
                }
            }
            if (length > 0 && done != length) error("Incomplete download")
            withContext(Dispatchers.Main) { report(done, length, speed) }
        } finally {
            handle?.dispose()
            connection.disconnect()
        }
    }

    private fun report(done: Long, length: Long, speed: Long) {
        downloadedBytes = done
        bytesPerSecond = speed
        progress = if (length > 0) (done.toFloat() / length).coerceIn(0f, 1f) else 0f
    }

    private fun install(context: Context, apk: File) {
        val opened = runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun installedVersionCode(context: Context): Long {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun clearStaleApk(context: Context, installed: Long) {
        val prefs = state(context)
        if (prefs.getLong(KEY_CACHED_APK_VERSION_CODE, -1L) <= installed) {
            File(context.cacheDir, "updates/update.apk").delete()
            prefs.edit().remove(KEY_CACHED_APK_VERSION_CODE).apply()
        }
    }

    private fun installedArch(context: Context): String {
        val info = context.applicationInfo
        val abis = mutableSetOf<String>()
        (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).filterNotNull().forEach { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().toList()
                        .map { it.name }
                        .filter { it.startsWith("lib/") && it.endsWith(".so") }
                        .forEach { abis += it.removePrefix("lib/").substringBefore('/') }
                }
            }
        }
        return abis.singleOrNull() ?: ARCH_UNIVERSAL
    }
}
