package com.hikari.app.aniyomi

import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads a package's metadata straight out of an `.apk`.
 *
 * Android's `PackageManager.getPackageArchiveInfo()` parses the APK's BINARY
 * `AndroidManifest.xml` (the `AXML` container) and returns a `PackageInfo`. There
 * is no such API on the JVM, and an Aniyomi extension's whole identity lives in
 * that file:
 *
 *   - the package name and version,
 *   - the `tachiyomi.animeextension` **uses-feature** that marks the APK as an
 *     extension at all,
 *   - the `<meta-data>` entries the loader reads (the source class list, the
 *     display name, the extensions-lib version, the content flags).
 *
 * This walks that structure directly. It is a deliberately small reader: it
 * understands the chunk layout, the UTF-8/UTF-16 string pool and typed attribute
 * values, and ignores everything else in the file.
 */
object ApkManifest {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102

    private const val UTF8_FLAG = 0x00000100

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val NO_INDEX = -1

    private class Attr(val name: String?, val resId: Int, val value: String?)

    private class Pool(val strings: List<String>, val utf8: Boolean) {
        /** The pool entry at [index], or null when the index is out of range
         *  (a malformed manifest must never throw mid-parse). */
        fun name(index: Int): String? = strings.getOrNull(index)
    }

    /** The `PackageInfo` for [apk], or null when the file is not a readable APK. */
    fun read(apk: File, flags: Int = 0): PackageInfo? {
        if (!apk.isFile) return null
        val xml = extractManifest(apk) ?: return null
        return runCatching { parse(xml) }.getOrNull()
    }

    private fun extractManifest(apk: File): ByteArray? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@runCatching null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    private fun parse(bytes: ByteArray): PackageInfo? {
        if (bytes.size < 8) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (u16(buf, 0) != RES_XML_TYPE) return null

        var pool: Pool? = null
        var resourceIds: IntArray? = null
        var position = u16(buf, 2)

        val info = PackageInfo()
        val app = ApplicationInfo()
        val meta = Bundle()
        val features = ArrayList<FeatureInfo>()
        var appLabel: String? = null

        while (position + 8 <= bytes.size) {
            val type = u16(buf, position)
            val headerSize = u16(buf, position + 2)
            val chunkSize = buf.getInt(position + 4)
            if (chunkSize <= 0 || position + chunkSize > bytes.size) break

            when (type) {
                RES_STRING_POOL -> pool = readStringPool(buf, position, headerSize, chunkSize)
                RES_XML_RESOURCE_MAP -> {
                    val count = (chunkSize - headerSize) / 4
                    val ids = IntArray(count)
                    for (i in 0 until count) ids[i] = buf.getInt(position + headerSize + i * 4)
                    resourceIds = ids
                }
                RES_XML_START_ELEMENT -> {
                    val p = pool
                    if (p != null) {
                        val element = readElement(buf, position, headerSize, p, resourceIds)
                        if (element != null) {
                            when (element.name) {
                                "manifest" -> {
                                    info.packageName = element.attr("package")
                                    element.attr("versionCode")?.toIntOrNull()?.let { info.versionCode = it }
                                    element.attr("versionCodeMajor")?.toIntOrNull()?.let { info.versionCodeMajor = it }
                                    element.attr("versionName")?.let { info.versionName = it }
                                    app.packageName = info.packageName
                                }
                                "uses-feature" -> {
                                    val n = element.attr("name")
                                    if (!n.isNullOrBlank()) features.add(FeatureInfo(n))
                                }
                                "application" -> {
                                    element.attr("label")?.let { if (!it.startsWith("@")) appLabel = it }
                                }
                                "meta-data" -> {
                                    val key = element.attr("name")
                                    if (!key.isNullOrBlank()) meta.putString(key, element.attr("value"))
                                }
                            }
                        }
                    }
                }
            }
            position += chunkSize
        }

        info.applicationInfo = app
        app.metaData = meta
        app.nonLocalizedLabel = appLabel
        info.reqFeatures = features.toTypedArray()
        if (info.packageName.isNullOrBlank()) return null
        return info
    }

    private class Element(val name: String?, private val attrs: List<Attr>) {
        fun attr(name: String): String? =
            attrs.firstOrNull { it.name == name }?.value
    }

    private fun readElement(
        buf: ByteBuffer,
        chunkStart: Int,
        headerSize: Int,
        pool: Pool,
        resourceIds: IntArray?,
    ): Element? {
        val ext = chunkStart + headerSize
        if (ext + 20 > buf.capacity()) return null
        val nameIndex = buf.getInt(ext + 4)
        val attributeStart = u16(buf, ext + 8)
        val attributeSize = u16(buf, ext + 10)
        val attributeCount = u16(buf, ext + 12)
        val attributesAt = ext + attributeStart
        val attrs = ArrayList<Attr>(attributeCount)
        for (i in 0 until attributeCount) {
            val p = attributesAt + i * attributeSize
            if (p + attributeSize > buf.capacity()) break
            val nameIdx = buf.getInt(p + 4)
            val rawValue = buf.getInt(p + 8)
            val dataType = buf.get(p + 15).toInt() and 0xFF
            val data = buf.getInt(p + 16)
            attrs.add(Attr(pool.name(nameIdx), resIdOf(nameIdx, resourceIds), valueOf(pool, dataType, data, rawValue)))
        }
        return Element(pool.name(nameIndex), attrs)
    }

    private fun resIdOf(index: Int, resourceIds: IntArray?): Int =
        if (index in 0 until (resourceIds?.size ?: 0)) resourceIds!![index] else NO_INDEX

    private fun valueOf(pool: Pool, dataType: Int, data: Int, rawValue: Int): String? = when {
        dataType == TYPE_STRING -> pool.name(data)
        rawValue != NO_INDEX && rawValue >= 0 -> pool.name(rawValue)
        dataType == TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
        dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX -> data.toString()
        else -> data.toString()
    }

    private fun readStringPool(buf: ByteBuffer, chunkStart: Int, headerSize: Int, chunkSize: Int): Pool? {
        if (chunkStart + 28 > buf.capacity()) return null
        val count = buf.getInt(chunkStart + 8)
        val flags = buf.getInt(chunkStart + 16)
        val stringsStart = buf.getInt(chunkStart + 20)
        if (count < 0 || count > 200_000) return null
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsAt = chunkStart + headerSize
        val dataAt = chunkStart + stringsStart
        val limit = chunkStart + chunkSize
        val out = ArrayList<String>(count)
        for (i in 0 until count) {
            val offsetAt = offsetsAt + i * 4
            if (offsetAt + 4 > limit) { out.add(""); continue }
            val offset = buf.getInt(offsetAt)
            out.add(if (utf8) readUtf8(buf, dataAt + offset, limit) else readUtf16(buf, dataAt + offset, limit))
        }
        return Pool(out, utf8)
    }

    /** UTF-8 pool entries: `[charCount varint][byteCount varint][bytes][0x00]`,
     *  padded to a 4-byte boundary. */
    private fun readUtf8(buf: ByteBuffer, at: Int, limit: Int): String {
        if (at < 0 || at >= limit) return ""
        var p = at
        val charCount = readVarint8(buf, p)
        p = charCount.second
        val byteCount = readVarint8(buf, p)
        p = byteCount.second
        val length = byteCount.first
        if (length < 0 || p + length > limit) return ""
        val bytes = ByteArray(length)
        for (i in 0 until length) bytes[i] = buf.get(p + i)
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
    }

    /** UTF-16 pool entries: `[charCount u16le][chars][0x0000]`. */
    private fun readUtf16(buf: ByteBuffer, at: Int, limit: Int): String {
        if (at < 0 || at + 2 > limit) return ""
        // The length is a 1-or-2 unit varint: a high bit means it continues.
        var length = u16(buf, at)
        var p = at + 2
        if ((length and 0x8000) != 0) {
            length = ((length and 0x7FFF) shl 16) or u16(buf, p)
            p += 2
        }
        if (length < 0 || p + length * 2 > limit) return ""
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = u16(buf, p + i * 2).toChar()
        return String(chars)
    }

    private fun readVarint8(buf: ByteBuffer, at: Int): Pair<Int, Int> {
        var p = at
        var value = buf.get(p).toInt() and 0xFF
        p++
        if ((value and 0x80) != 0) {
            value = ((value and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p++
        }
        return value to p
    }

    private fun u16(buf: ByteBuffer, at: Int): Int =
        if (at + 2 > buf.capacity()) 0 else buf.getShort(at).toInt() and 0xFFFF
}
