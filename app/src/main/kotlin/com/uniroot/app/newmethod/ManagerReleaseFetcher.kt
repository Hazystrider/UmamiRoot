package com.uniroot.app.newmethod

import android.content.Context
import com.uniroot.app.engine.KernelManagerType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fetches official arm64 `ksud` binaries from third-party KernelSU fork GitHub
 * releases into DFBridge's custom `ksud` slot (`ksud-custom-classic` or
 * `ksud-custom-next`), prioritizing any Samsung KDP-tagged `ksud` asset first.
 */
object ManagerReleaseFetcher {

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val isKdpTagged: Boolean,
        val kind: AssetKind,
    )

    enum class AssetKind {
        RAW_KSUD,
        ZIP_ARCHIVE,
        APK_EMBEDDED_KSUD,
    }

    data class FetchResult(
        val repo: String,
        val tag: String,
        val assetName: String,
        val kdpTagged: Boolean,
        val bytes: Long,
    )

    private const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_ASSET_BYTES = 120L * 1024L * 1024L

    /**
     * Picks the best arm64 `ksud` asset from a GitHub release's `assets` list:
     * 1. Samsung/KDP-tagged raw `ksud` (aarch64/arm64)
     * 2. Samsung/KDP-tagged `.zip` containing `ksud`
     * 3. Standard raw `ksud` binary (`ksud-aarch64-linux-android`, etc.)
     * 4. Standard `ksud` `.zip` (`ksud-aarch64-linux-android.zip`, etc.)
     * 5. Manager `.apk` embedding `lib/arm64-v8a/libksud.so`
     */
    internal fun selectBestKsudAsset(assets: List<Triple<String, String, Long>>): ReleaseAsset? {
        fun isWrongArch(lower: String): Boolean =
            lower.contains("x86_64") || lower.contains("x86") || lower.contains("amd64") ||
                lower.contains("riscv") || lower.contains("armv7") || lower.contains("armeabi") ||
                lower.contains("windows") || lower.contains("macos") || lower.contains("darwin")

        fun isKdp(lower: String): Boolean =
            lower.contains("kdp") || lower.contains("samsung")

        fun isArm64OrNeutral(lower: String): Boolean =
            !isWrongArch(lower) && (
                lower.contains("aarch64") || lower.contains("arm64") ||
                    (!lower.contains("x86") && !lower.contains("arm32"))
                )

        val candidates = assets.mapNotNull { (name, url, size) ->
            val lower = name.lowercase()
            if (isWrongArch(lower) || lower.endsWith(".sha256") || lower.endsWith(".sig") || lower.endsWith(".asc")) {
                return@mapNotNull null
            }
            val kdp = isKdp(lower)
            when {
                lower.contains("ksud") && !lower.endsWith(".zip") && !lower.endsWith(".apk") &&
                    !lower.endsWith(".tar.gz") && !lower.endsWith(".ko") && isArm64OrNeutral(lower) ->
                    ReleaseAsset(name, url, size, kdp, AssetKind.RAW_KSUD)
                lower.contains("ksud") && lower.endsWith(".zip") && isArm64OrNeutral(lower) ->
                    ReleaseAsset(name, url, size, kdp, AssetKind.ZIP_ARCHIVE)
                lower.endsWith(".apk") && !lower.contains("debug") ->
                    ReleaseAsset(name, url, size, kdp, AssetKind.APK_EMBEDDED_KSUD)
                else -> null
            }
        }

        fun score(a: ReleaseAsset): Int {
            var s = 0
            if (a.isKdpTagged) s += 100
            s += when (a.kind) {
                AssetKind.RAW_KSUD -> 40
                AssetKind.ZIP_ARCHIVE -> 30
                AssetKind.APK_EMBEDDED_KSUD -> 10
            }
            val lower = a.name.lowercase()
            if (lower.contains("aarch64") || lower.contains("arm64")) s += 5
            return s
        }

        return candidates.maxByOrNull { score(it) }
    }

    fun fetchLatest(
        context: Context,
        manager: KernelManagerType,
        destFile: File,
        log: (String) -> Unit,
    ): FetchResult {
        val repos = manager.githubRepos
        require(repos.isNotEmpty()) { "No GitHub release repo configured for ${manager.displayName}" }

        var lastError: Throwable? = null
        for (repo in repos) {
            try {
                log("Checking GitHub releases for $repo…")
                val release = fetchLatestReleaseJson(repo)
                val tag = release.optString("tag_name", "latest")
                val assetsArr = release.optJSONArray("assets") ?: JSONArray()
                val rawAssets = buildList {
                    for (i in 0 until assetsArr.length()) {
                        val o = assetsArr.getJSONObject(i)
                        add(
                            Triple(
                                o.optString("name", ""),
                                o.optString("browser_download_url", ""),
                                o.optLong("size", -1L),
                            ),
                        )
                    }
                }
                val chosen = selectBestKsudAsset(rawAssets)
                    ?: error("No arm64 ksud or manager APK found in $repo ($tag)")

                log("Selected ${chosen.name} ($tag${if (chosen.isKdpTagged) ", KDP-tagged" else ""})")
                val tmp = File(destFile.parentFile ?: context.filesDir, "${destFile.name}.dl")
                try {
                    when (chosen.kind) {
                        AssetKind.RAW_KSUD -> downloadRaw(chosen.downloadUrl, tmp)
                        AssetKind.ZIP_ARCHIVE -> downloadAndExtractFromZip(
                            url = chosen.downloadUrl,
                            dest = tmp,
                            matcher = { entry ->
                                val e = entry.lowercase()
                                (e.endsWith("/ksud") || e == "ksud" || e.endsWith("ksud-aarch64-linux-android") ||
                                    e.endsWith("lib/arm64-v8a/libksud.so")) && !e.contains("x86")
                            },
                        )
                        AssetKind.APK_EMBEDDED_KSUD -> downloadAndExtractFromZip(
                            url = chosen.downloadUrl,
                            dest = tmp,
                            matcher = { entry ->
                                val e = entry.lowercase()
                                e == "lib/arm64-v8a/libksud.so" || e.endsWith("/arm64-v8a/libksud.so")
                            },
                        )
                    }

                    require(tmp.length() > 100_000L) { "Downloaded ksud is too small (${tmp.length()} bytes)" }
                    val magic = ByteArray(4)
                    tmp.inputStream().use { it.read(magic) }
                    require(
                        magic[0] == 0x7f.toByte() &&
                            magic[1] == 'E'.code.toByte() &&
                            magic[2] == 'L'.code.toByte() &&
                            magic[3] == 'F'.code.toByte(),
                    ) { "Downloaded file is not a valid ELF binary" }

                    if (destFile.exists()) destFile.delete()
                    require(tmp.renameTo(destFile)) { "Could not save ${destFile.name}" }
                    destFile.setReadable(true, false)
                    destFile.setExecutable(true, false)
                    return FetchResult(
                        repo = repo,
                        tag = tag,
                        assetName = chosen.name,
                        kdpTagged = chosen.isKdpTagged,
                        bytes = destFile.length(),
                    )
                } finally {
                    if (tmp.exists()) runCatching { tmp.delete() }
                }
            } catch (e: Throwable) {
                lastError = e
                log("Repo $repo failed: ${e.message}")
            }
        }
        throw IllegalStateException(lastError?.message ?: "Could not fetch release for ${manager.displayName}", lastError)
    }

    private fun fetchLatestReleaseJson(repo: String): JSONObject {
        fun hasSelectableAsset(release: JSONObject): Boolean {
            val arr = release.optJSONArray("assets") ?: return false
            if (arr.length() == 0) return false
            val raw = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Triple(o.optString("name", ""), o.optString("browser_download_url", ""), o.optLong("size", -1L)))
                }
            }
            return selectBestKsudAsset(raw) != null
        }

        val latest = runCatching {
            JSONObject(downloadText("https://api.github.com/repos/$repo/releases/latest"))
        }.getOrNull()
        if (latest != null && hasSelectableAsset(latest)) {
            return latest
        }
        val list = JSONArray(downloadText("https://api.github.com/repos/$repo/releases?per_page=10"))
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (hasSelectableAsset(item)) return item
        }
        error("No published release assets found on $repo")
    }

    private fun downloadText(url: String): String {
        val conn = open(url)
        return try {
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(out.size() + n <= MAX_JSON_BYTES) { "Release metadata too large" }
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadRaw(url: String, dest: File) {
        val conn = open(url)
        try {
            conn.inputStream.use { input ->
                copyWithLimit(input, dest)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndExtractFromZip(
        url: String,
        dest: File,
        matcher: (String) -> Boolean,
    ) {
        val conn = open(url)
        try {
            ZipInputStream(conn.inputStream).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory && matcher(entry.name)) {
                        copyWithLimit(zis, dest)
                        return
                    }
                }
            }
            error("Archive does not contain an arm64 ksud / libksud.so binary")
        } finally {
            conn.disconnect()
        }
    }

    private fun copyWithLimit(input: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_ASSET_BYTES) { "Asset exceeds size limit" }
                out.write(buf, 0, n)
            }
            out.fd.sync()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UniRoot/6.0.0")
            setRequestProperty("Accept", "application/vnd.github+json")
            connect()
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode for $url" }
        }
}
