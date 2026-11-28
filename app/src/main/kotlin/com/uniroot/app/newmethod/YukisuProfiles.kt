package com.uniroot.app.newmethod

import android.content.Context
import java.io.File

/**
 * YukiSU ksud profiles for the new method — mirrors KsudNextProfiles & SukisuNovaProfiles.
 *
 * TWO SOURCES:
 *  1. EMBEDDED — assets/df/ksud-yukisu/<id> (+ optional <id>.txt sidecar).
 *  2. DYNAMIC  — filesDir/ksud-yukisu/<id>: downloaded by DfKsudUpdater.
 */
object YukisuProfiles {

    internal data class ParsedProfileId(
        val version: String,
        val variant: String,
        val label: String,
    )

    /**
     * Parses YukiSU profile IDs:
     * - "yukisu-kdp-20261128-012000" -> version="20261128.012000", variant="kdp-samsung", label="20261128 · kdp-samsung"
     * - "1.9.0-kdp-samsung"          -> version="1.9.0", variant="kdp-samsung", label="1.9.0 · kdp-samsung"
     * - "1.9.0-yukisu"               -> version="1.9.0", variant="yukisu", label="1.9.0 · yukisu"
     */
    internal fun parseProfileId(name: String): ParsedProfileId {
        val kdpDate = Regex("^yukisu-kdp-(\\d{8})(?:-(\\d{6}))?$").matchEntire(name)
        if (kdpDate != null) {
            val date = kdpDate.groupValues[1]
            val time = kdpDate.groupValues[2]
            val ver = if (time.isNotEmpty()) "$date.$time" else date
            return ParsedProfileId(version = ver, variant = "kdp-samsung", label = "$date · kdp-samsung")
        }
        val prefixed = Regex("^(.+)-v(\\d+(?:\\.\\d+)*)$").matchEntire(name)
        if (prefixed != null) {
            val variant = prefixed.groupValues[1]
            val version = prefixed.groupValues[2]
            return ParsedProfileId(version = version, variant = variant, label = name)
        }
        val dash = name.indexOf('-')
        val version = if (dash > 0) name.substring(0, dash) else name
        val variant = if (dash > 0) name.substring(dash + 1) else ""
        val label = if (variant.isEmpty()) version else "$version · $variant"
        return ParsedProfileId(version = version, variant = variant, label = label)
    }

    private const val DIR = "df/ksud-yukisu"
    private const val PREFS = "uniroot_prefs"
    private const val PREF_KEY = "yukisu_profile"
    private const val MODE = Context.MODE_PRIVATE

    /** Dynamic profiles folder (downloaded by DfKsudUpdater). */
    fun dynamicDir(context: Context): File =
        File(context.filesDir, "ksud-yukisu").apply { mkdirs() }

    /** Embedded + dynamic profiles sorted descending by version, then variant. */
    fun list(context: Context): List<KsudNextProfile> {
        val names: List<String> = runCatching {
            context.assets.list(DIR)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        val embedded = names.filter { it.isNotEmpty() && !it.endsWith(".txt") }
            .map { name -> profileFromId(context, name, dynamic = false) }
        val dynamic = runCatching {
            dynamicDir(context).listFiles { f -> f.isFile && !f.name.endsWith(".txt") }
                ?.map { it.name }
                ?.map { name -> profileFromId(context, name, dynamic = true) }
                ?: emptyList()
        }.getOrDefault(emptyList())
        return (embedded + dynamic)
            .distinctBy { it.id }
            .sortedWith { a, b ->
                val ra = versionRank(a.version)
                val rb = versionRank(b.version)
                for (i in 0 until maxOf(ra.size, rb.size)) {
                    val x = ra.getOrElse(i) { 0 }
                    val y = rb.getOrElse(i) { 0 }
                    if (x != y) return@sortedWith y - x
                }
                a.variant.compareTo(b.variant)
            }
    }

    private fun profileFromId(context: Context, name: String, dynamic: Boolean): KsudNextProfile {
        val parsed = parseProfileId(name)
        val note = if (dynamic) {
            runCatching { sidecar(context, name).readText().lineSequence().firstOrNull()?.trim() ?: "" }
                .getOrDefault("")
        } else {
            runCatching {
                context.assets.open("$DIR/$name.txt").bufferedReader().useLines { lines ->
                    lines.firstOrNull()?.trim() ?: ""
                }
            }.getOrDefault("")
        }
        return KsudNextProfile(
            id = name,
            version = parsed.version,
            variant = parsed.variant,
            note = note,
            dynamic = dynamic,
            label = parsed.label,
        )
    }

    private fun sidecar(context: Context, id: String): File =
        File(dynamicDir(context), "$id.txt")

    fun default(context: Context): KsudNextProfile? = list(context).firstOrNull()

    fun selectedId(context: Context): String =
        context.getSharedPreferences(PREFS, MODE).getString(PREF_KEY, "") ?: ""

    fun selected(context: Context): KsudNextProfile? {
        val profiles = list(context)
        if (profiles.isEmpty()) return null
        return profiles.firstOrNull { it.id == selectedId(context) } ?: profiles.first()
    }

    fun setSelected(context: Context, id: String) {
        context.getSharedPreferences(PREFS, MODE).edit().putString(PREF_KEY, id).apply()
    }

    fun selectedAsset(context: Context): String? {
        val p = selected(context) ?: return null
        return if (p.dynamic) null else "$DIR/${p.id}"
    }

    @JvmStatic
    fun selectedFile(context: Context): File? {
        val p = selected(context) ?: return null
        return if (p.dynamic) File(dynamicDir(context), p.id) else null
    }

    fun addDynamic(context: Context, id: String, bytes: ByteArray, note: String) {
        val f = File(dynamicDir(context), id)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IllegalStateException("rename $id")
        }
        sidecar(context, id).writeText(note)
        setSelected(context, id)
    }

    @JvmStatic
    fun resolveAsset(context: Context): String? = selectedAsset(context)

    fun deleteDynamic(context: Context, id: String): Boolean {
        val f = File(dynamicDir(context), id)
        if (!f.isFile) return false
        sidecar(context, id).delete()
        return f.delete()
    }

    private fun versionRank(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: 0 }
}
