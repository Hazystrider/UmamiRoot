package com.uniroot.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

enum class KernelManagerType(
    val id: String,
    val displayName: String,
    val summary: String,
    val prefersNextBackend: Boolean?,
    val knownPackages: List<String>,
    val labelKeywords: List<String>,
    val prefersCustomKsud: Boolean = false,
    val githubRepos: List<String> = emptyList(),
) {
    AUTO(
        id = "auto",
        displayName = "Auto-detect",
        summary = "Matches the selected KernelSU / KernelSU Next backend automatically.",
        prefersNextBackend = null,
        knownPackages = emptyList(),
        labelKeywords = emptyList(),
    ),
    KERNELSU(
        id = "kernelsu",
        displayName = "KernelSU (Classic)",
        summary = "Official KernelSU manager (me.weishu.kernelsu) with bundled classic ksud.",
        prefersNextBackend = false,
        knownPackages = listOf("me.weishu.kernelsu", "me.weishu.kernelsu.pr"),
        labelKeywords = listOf("KernelSU"),
        githubRepos = listOf("tiann/KernelSU"),
    ),
    KERNELSU_NEXT(
        id = "kernelsu_next",
        displayName = "KernelSU Next",
        summary = "Official KernelSU Next (standard & -spoofed builds) with bundled Next ksud.",
        prefersNextBackend = true,
        knownPackages = listOf("com.rifsxd.ksunext", "com.rifsxd.ksunext.debug"),
        labelKeywords = listOf("KernelSU-Next", "KernelSU Next", "KSUNext"),
        githubRepos = listOf("KernelSU-Next/KernelSU-Next"),
    ),
    SUKISU_NOVA(
        id = "sukisu_nova",
        displayName = "SukiSU-Nova (Ultra)",
        summary = "Uses the currently active KDP ksud by default. Supports SUSFS UI & KPM; fetch from GitHub or import a custom ksud override.",
        prefersNextBackend = null,
        knownPackages = listOf("com.sukisu.ultra", "com.sukisu.nova", "com.shirkneko.sukisu"),
        labelKeywords = listOf("SukiSU", "SukiSU-Nova", "SukiSU Ultra"),
        prefersCustomKsud = true,
        githubRepos = listOf("hazystrider/UmamiRoot", "SukiSU-Nova/SukiSU-Nova"),
    ),
    BAKASU(
        id = "bakasu",
        displayName = "BakaSU (ReSukiSU)",
        summary = "Uses the currently active KDP ksud by default. Multi-manager interop (pairs with official KernelSU or KowSU); fetch from GitHub or import custom ksud.",
        prefersNextBackend = null,
        knownPackages = listOf("com.resukisu.resukisu", "com.bakasu.manager", "com.bakasu.bakasu"),
        labelKeywords = listOf("BakaSU", "ReSukiSU"),
        prefersCustomKsud = true,
        githubRepos = listOf("Baka-SU/BakaSU"),
    ),
    YUKISU(
        id = "yukisu",
        displayName = "YukiSU",
        summary = "Uses the currently active KDP ksud by default. C++ manager with MetaModule & dynamic spoofing; fetch from GitHub or import custom ksud.",
        prefersNextBackend = null,
        knownPackages = listOf("com.yukisu.yukisu", "moe.yukisu.manager", "com.yukisu.manager"),
        labelKeywords = listOf("YukiSU"),
        prefersCustomKsud = true,
        githubRepos = listOf("Rouyashiki/YukiSU"),
    ),
    XECKERNEL(
        id = "xeckernel",
        displayName = "XECKernel Ultra",
        summary = "Uses the currently active KDP ksud by default on GKI 2.0 (5.10+); import an XECKernel ksud binary if using its own signed LKM.",
        prefersNextBackend = null,
        knownPackages = listOf("com.xeckernel.ultra", "com.xec.kernel", "com.xeckernel.manager"),
        labelKeywords = listOf("XECKernel", "XEC Kernel"),
        prefersCustomKsud = true,
    ),
    RKSU_WILDKSU(
        id = "rksu_wildksu",
        displayName = "RKSU / WildKSU",
        summary = "Uses the currently active KDP ksud by default; fetch WildKSU / RKSU ksud from GitHub or import a custom ksud override.",
        prefersNextBackend = null,
        knownPackages = listOf(
            "com.rsuntk.rksu",
            "me.weishu.kernelsu.rksu",
            "com.wildksu.manager",
            "com.wild.ksu",
            "me.weishu.kernelsu",
        ),
        labelKeywords = listOf("RKSU", "WildKSU", "Wild KSU"),
        prefersCustomKsud = true,
        githubRepos = listOf("WildKernels/Wild_KSU", "DXRN-MoonWake/RKSU"),
    ),
    KOWSU(
        id = "kowsu",
        displayName = "KowSU",
        summary = "Uses the currently active KDP ksud by default. Frontend manager APK — pairs with BakaSU or upstream LKM ksud.",
        prefersNextBackend = null,
        knownPackages = listOf("com.kowsu.manager", "me.kowsu.kernelsu", "com.kernelsu.kowsu"),
        labelKeywords = listOf("KowSU", "KoWSU"),
        prefersCustomKsud = true,
        githubRepos = listOf("deepongi-labs/KernelSU-KoWSU"),
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom manager",
        summary = "Uses the currently active KDP ksud by default with any installed/spoofed manager package ID; optional custom ksud override available.",
        prefersNextBackend = null,
        knownPackages = emptyList(),
        labelKeywords = emptyList(),
        prefersCustomKsud = true,
    );

    companion object {
        fun fromId(id: String?): KernelManagerType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

data class KsuManagerMatch(
    val packageName: String,
    val launcherActivity: String?,
    val spoofed: Boolean,
    val managerName: String = "",
    val versionName: String? = null,
)

/**
 * Detects installed KernelSU ecosystem managers (Classic, Next, SukiSU-Nova,
 * BakaSU/ReSukiSU, YukiSU, XECKernel Ultra, RKSU/WildKSU, KowSU, and Custom),
 * including dynamically spoofed builds via signing certificate SHA-256 and
 * app label / version metadata.
 */
object KsuManagerDetector {

    /** Official KernelSU Next release signing certificate SHA-256 (standard + spoofed). */
    internal const val NEXT_CERT_SHA256 =
        "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7"

    /** Official KernelSU classic release signing certificate SHA-256. */
    internal const val CLASSIC_CERT_SHA256 =
        "c371061b19d8c7d7d6133c6a9bafe198fa944e50c1b31c9d8daa8d7f1fc2d2d6"

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun matchesCertificate(certHashes: Collection<String>, next: Boolean): Boolean {
        val expected = if (next) NEXT_CERT_SHA256 else CLASSIC_CERT_SHA256
        return certHashes.any { it.equals(expected, ignoreCase = true) }
    }

    internal fun matchesSpoofedFallback(
        next: Boolean,
        versionName: String?,
        appLabel: String?,
    ): Boolean {
        if (!next) return false
        val ver = versionName.orEmpty()
        val label = appLabel.orEmpty()
        return ver.contains("spoofed", ignoreCase = true) &&
            (label.contains("KernelSU", ignoreCase = true) || label.contains("KSU", ignoreCase = true))
    }

    internal fun matchesManagerMetadata(
        type: KernelManagerType,
        packageName: String,
        appLabel: String?,
        versionName: String?,
        certHashes: Collection<String> = emptyList(),
    ): Boolean {
        if (type.knownPackages.any { it.equals(packageName, ignoreCase = true) }) return true
        return when (type) {
            KernelManagerType.KERNELSU -> matchesCertificate(certHashes, next = false)
            KernelManagerType.KERNELSU_NEXT ->
                matchesCertificate(certHashes, next = true) ||
                    matchesSpoofedFallback(next = true, versionName = versionName, appLabel = appLabel)
            KernelManagerType.SUKISU_NOVA,
            KernelManagerType.BAKASU,
            KernelManagerType.YUKISU,
            KernelManagerType.XECKERNEL,
            KernelManagerType.RKSU_WILDKSU,
            KernelManagerType.KOWSU -> {
                val label = appLabel.orEmpty()
                type.labelKeywords.any { kw -> label.contains(kw, ignoreCase = true) }
            }
            KernelManagerType.AUTO, KernelManagerType.CUSTOM -> false
        }
    }

    fun find(context: Context, next: Boolean): KsuManagerMatch? =
        find(context, KernelManagerType.AUTO, ksuNextFallback = next, customPackage = "")

    fun find(
        context: Context,
        managerType: KernelManagerType,
        ksuNextFallback: Boolean,
        customPackage: String = "",
    ): KsuManagerMatch? {
        val pm = context.packageManager
        val effectiveType = if (managerType == KernelManagerType.AUTO) {
            if (ksuNextFallback) KernelManagerType.KERNELSU_NEXT else KernelManagerType.KERNELSU
        } else {
            managerType
        }

        if (effectiveType == KernelManagerType.CUSTOM) {
            val pkg = customPackage.trim()
            if (pkg.isEmpty()) return null
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
            val activity = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val label = runCatching {
                info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
            }.getOrNull().orEmpty().ifBlank { pkg }
            return KsuManagerMatch(pkg, activity, spoofed = true, managerName = label, versionName = info.versionName)
        }

        // Fast path: known package IDs for the selected manager.
        for (pkg in effectiveType.knownPackages) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            val activity = pm.getLaunchIntentForPackage(pkg)?.component?.className
            return KsuManagerMatch(
                packageName = pkg,
                launcherActivity = activity,
                spoofed = false,
                managerName = effectiveType.displayName,
                versionName = info.versionName,
            )
        }

        // Scan launchable apps for dynamically spoofed or renamed builds.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        var bestMatch: Pair<Long, KsuManagerMatch>? = null
        val seen = HashSet<String>()
        for (ri in resolved) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (!seen.add(pkg)) continue
            val pkgInfo = runCatching {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull() ?: continue

            val hashes = signerHashes(pkgInfo)
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
            if (matchesManagerMetadata(effectiveType, pkg, label, pkgInfo.versionName, hashes)) {
                val versionCode = pkgInfo.longVersionCode
                val candidate = KsuManagerMatch(
                    packageName = pkg,
                    launcherActivity = ai.name,
                    spoofed = pkg !in effectiveType.knownPackages,
                    managerName = label?.takeIf { it.isNotBlank() } ?: effectiveType.displayName,
                    versionName = pkgInfo.versionName,
                )
                if (bestMatch == null || versionCode > bestMatch.first) {
                    bestMatch = versionCode to candidate
                }
            }
        }
        return bestMatch?.second
    }

    private fun signerHashes(pkgInfo: PackageInfo): List<String> {
        val signingInfo = pkgInfo.signingInfo ?: return emptyList()
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptyList()
        return signers.map { sha256Hex(it.toByteArray()) }
    }
}
