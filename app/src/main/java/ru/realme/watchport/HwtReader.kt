package ru.realme.watchport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** One decoded picture from the HWT FAT. HWT picture IDs used in protobuf are one-based. */
internal data class HwtImageAsset(val id: Int, val bitmap: Bitmap)

/** A digit or text strip group found in the Huawei protobuf layout. */
internal data class HwtSpriteGroup(
    val section: Int,
    val assetIds: List<Int>,
    val x: Int,
    val y: Int,
    /** HWT horizontal alignment: 0=start, 1=center, 2=end. */
    val alignment: Int = 0
)

/** A live item position from the HWT main-screen layout. */
internal data class HwtWidgetPlacement(
    val kind: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/** A fixed HWT image (caption, symbol, or decoration) that sits above the base artwork. */
internal data class HwtImagePlacement(val assetId: Int, val x: Int, val y: Int)

internal data class HwtInfo(
    val displayName: String,
    val faceName: String,
    val preview: Bitmap,
    /** Static HWT artwork layer, not the flattened screenshot with sample values. */
    val artwork: Bitmap,
    val imageCount: Int,
    val artworkWidth: Int,
    val artworkHeight: Int,
    val assets: List<HwtImageAsset>,
    val spriteGroups: List<HwtSpriteGroup>,
    val widgetPlacements: List<HwtWidgetPlacement>,
    val staticImagePlacements: List<HwtImagePlacement>,
    val warnings: List<String>
)

internal object HwtReader {
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    private const val HEADER_SIZE = 16
    private val imageMagic = byteArrayOf(0x45, 0x23, 0x88.toByte(), 0x88.toByte())

    fun inspect(context: Context, uri: Uri): HwtInfo {
        var displayName = "watchface.hwt"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) displayName = cursor.getString(index) ?: displayName
            }
        }

        var faceData: ByteArray? = null
        var cover: ByteArray? = null
        var icon: ByteArray? = null
        var titleFromXml: String? = null
        context.contentResolver.openInputStream(uri)?.use { source ->
            ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = entry.name.replace('\\', '/')
                    when {
                        normalized == "com.huawei.watchface" -> faceData = readLimited(zip)
                        normalized.equals("preview/cover.jpg", true) -> cover = readLimited(zip)
                        normalized.equals("preview/icon_small.jpg", true) -> icon = readLimited(zip)
                        normalized.equals("description.xml", true) -> titleFromXml = readLimited(zip).decodeToString()
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot open selected document")

        val raw = faceData ?: error("Missing com.huawei.watchface")
        val payload = if (raw.size < HEADER_SIZE || raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) {
            openNestedFace(raw) ?: error("Unsupported HWT payload")
        } else raw

        val parsed = parseImages(payload)
        val artwork = chooseBackground(parsed.assets)
            ?: cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: error("No usable HWT background or preview image found")
        val preview = cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: icon?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: artwork
        val layout = parseLayout(payload, parsed.assets.size)
        Log.i("RealmeHwtLayout", "canvas=${artwork.width}x${artwork.height}; widgets=${layout.widgets}; groups=" +
            layout.spriteGroups.joinToString(" | ") { "s${it.section}@${it.x},${it.y}/${it.alignment}:${it.assetIds.size}" })
        val xmlTitle = titleFromXml?.let(::extractTitle)
        val filenameTitle = displayName.substringBeforeLast('.', displayName).ifBlank { "HWT face" }
        val title = xmlTitle?.takeIf { it.isNotBlank() } ?: filenameTitle
        val warnings = buildList {
            if (parsed.assets.none { it.bitmap.width >= 320 && it.bitmap.height >= 320 }) {
                add("The HWT has no full-size artwork layer; the preview is being used as a fallback.")
            }
            if (layout.spriteGroups.isEmpty()) add("The HWT text and digit layout could not be decoded.")
            if (layout.widgets.none { it.kind == 10 } || layout.widgets.none { it.kind == 12 }) {
                add("Some HWT health-item positions are missing; default positions will be used for them.")
            }
        }
        return HwtInfo(
            displayName = displayName,
            faceName = title,
            preview = preview,
            artwork = artwork,
            imageCount = parsed.imageCount,
            artworkWidth = artwork.width,
            artworkHeight = artwork.height,
            assets = parsed.assets,
            spriteGroups = layout.spriteGroups,
            widgetPlacements = layout.widgets,
            staticImagePlacements = layout.staticImagePlacements,
            warnings = warnings
        )
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_ENTRY_BYTES) { "HWT entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun openNestedFace(payload: ByteArray): ByteArray? = runCatching {
        ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return@use null
                val bytes = readLimited(zip)
                zip.closeEntry()
                if (entry.name.endsWith("com.huawei.watchface", true) && bytes.size >= HEADER_SIZE) return@use bytes
            }
            null
        }
    }.getOrNull()

    private data class ImageScan(val imageCount: Int, val assets: List<HwtImageAsset>)

    private fun parseImages(raw: ByteArray): ImageScan {
        if (raw.size < HEADER_SIZE) return ImageScan(0, emptyList())
        val protoLength = u16(raw, 2)
        val mapLength = u32(raw, 4)
        if (mapLength <= 0 || mapLength % 8L != 0L || mapLength > 1_000_000L) return ImageScan(0, emptyList())
        val tableStart = HEADER_SIZE.toLong() + protoLength
        val bodyStart = tableStart + mapLength + 8L
        if (tableStart + mapLength > raw.size || bodyStart > raw.size) return ImageScan(0, emptyList())
        val count = (mapLength / 8).toInt()
        val assets = ArrayList<HwtImageAsset>(count)
        for (index in 0 until count) {
            val entry = tableStart.toInt() + index * 8
            val relativeOffset = u32(raw, entry)
            val size = u32(raw, entry + 4)
            if (size < 8 || size > 32L * 1024 * 1024) continue
            val startLong = bodyStart + relativeOffset - 8L
            if (startLong < bodyStart || startLong + size > raw.size) continue
            val start = startLong.toInt()
            if (!raw.copyOfRange(start, start + 4).contentEquals(imageMagic)) continue
            val width = u16(raw, start + 4)
            val height = u16(raw, start + 6)
            if (width !in 1..4000 || height !in 1..4000) continue
            val pixels = decodeRle(raw, start, size.toInt(), width, height) ?: continue
            assets += HwtImageAsset(index + 1, Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
        }
        return ImageScan(count, assets)
    }

    /** A rendered HWT screenshot is opaque and contains sample time/metrics. Prefer the sparse base layer. */
    private fun chooseBackground(assets: List<HwtImageAsset>): Bitmap? {
        val candidates = assets.filter { it.bitmap.width >= 320 && it.bitmap.height >= 320 }
        if (candidates.isEmpty()) return assets.maxByOrNull { it.bitmap.width.toLong() * it.bitmap.height }?.bitmap
        data class BackgroundStats(val bitmap: Bitmap, val transparentRatio: Float, val visibleRatio: Float)
        val stats = candidates.map { asset ->
            val bitmap = asset.bitmap
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var transparent = 0
            var visibleColor = 0
            for (pixel in pixels) {
                if ((pixel ushr 24) < 245) transparent++
                if (((pixel ushr 16) and 0xff) > 8 || ((pixel ushr 8) and 0xff) > 8 || (pixel and 0xff) > 8) visibleColor++
            }
            val total = pixels.size.toFloat()
            BackgroundStats(bitmap, transparent / total, visibleColor / total)
        }

        // Many Huawei faces store a tiny separator/colon as a full-canvas black
        // image beside the actual static layer and the flattened sample preview.
        // The old sparsity score picked that separator as the background, which
        // erased the face's lines, icons, and other fixed artwork. Prefer an
        // opaque, moderately sparse layer when one exists; it is the usual base
        // artwork between the nearly blank overlays and the sample screenshot.
        val baseArtCandidates = stats.filter {
            it.transparentRatio < 0.01f && it.visibleRatio in 0.01f..0.12f
        }
        if (baseArtCandidates.isNotEmpty()) {
            return baseArtCandidates.minByOrNull { kotlin.math.abs(it.visibleRatio - 0.04f) }?.bitmap
        }

        // For other HWT variants, retain a conservative fallback that avoids
        // choosing an almost-empty layer whenever a more informative opaque
        // candidate is available.
        val nonEmpty = stats.filter { it.visibleRatio >= 0.005f }
        val pool = nonEmpty.ifEmpty { stats }
        return pool.minByOrNull { candidate ->
            candidate.transparentRatio * 0.8f + kotlin.math.abs(candidate.visibleRatio - 0.06f) * 0.2f
        }?.bitmap
    }

    private fun decodeRle(data: ByteArray, start: Int, length: Int, width: Int, height: Int): IntArray? {
        val pixelCount = width.toLong() * height
        if (pixelCount > 16_000_000L) return null
        val pixels = IntArray(pixelCount.toInt())
        var input = start + 8
        val end = start + length
        var output = 0
        while (output < pixels.size && input < end) {
            if (input + 4 <= end && data[input] == 0x89.toByte() && data[input + 1] == 0x67.toByte() &&
                data[input + 2] == 0x45.toByte() && data[input + 3] == 0x23.toByte()) {
                if (input + 12 > end) return null
                val blue = data[input + 4].toInt() and 0xff
                val green = data[input + 5].toInt() and 0xff
                val red = data[input + 6].toInt() and 0xff
                val alpha = data[input + 7].toInt() and 0xff
                val run = u32(data, input + 8).coerceAtMost(pixels.size.toLong()).toInt()
                if (run <= 0 || output + run > pixels.size) return null
                java.util.Arrays.fill(pixels, output, output + run, Color.argb(alpha, red, green, blue))
                output += run
                input += 12
            } else {
                if (input + 4 > end) return null
                val blue = data[input].toInt() and 0xff
                val green = data[input + 1].toInt() and 0xff
                val red = data[input + 2].toInt() and 0xff
                val alpha = data[input + 3].toInt() and 0xff
                pixels[output++] = Color.argb(alpha, red, green, blue)
                input += 4
            }
        }
        return pixels.takeIf { output == pixels.size }
    }

    private data class ProtoField(val number: Int, val wire: Int, val integer: Long? = null, val bytes: ByteArray? = null)
    private data class ParsedLayout(
        val spriteGroups: List<HwtSpriteGroup>,
        val widgets: List<HwtWidgetPlacement>,
        val staticImagePlacements: List<HwtImagePlacement>
    )

    private fun parseLayout(raw: ByteArray, imageCount: Int): ParsedLayout {
        if (raw.size < HEADER_SIZE) return ParsedLayout(emptyList(), emptyList(), emptyList())
        val protoLength = u16(raw, 2)
        if (HEADER_SIZE + protoLength > raw.size) return ParsedLayout(emptyList(), emptyList(), emptyList())
        val root = parseProto(raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + protoLength))
            ?: return ParsedLayout(emptyList(), emptyList(), emptyList())
        val sections = root.filter { it.number == 1 && it.bytes != null }
        val spriteGroups = ArrayList<HwtSpriteGroup>()
        val staticImages = ArrayList<HwtImagePlacement>()
        sections.forEachIndexed { sectionIndex, section ->
            collectSpriteGroups(section.bytes!!, sectionIndex, imageCount, spriteGroups, 0)
            collectStaticImagePlacements(section.bytes, imageCount, staticImages, 0)
        }
        val uniqueGroups = spriteGroups.distinctBy { "${it.section}:${it.x}:${it.y}:${it.alignment}:${it.assetIds.joinToString(",")}" }
        val widgets = ArrayList<HwtWidgetPlacement>()
        sections.getOrNull(1)?.bytes?.let { collectWidgets(it, widgets, 0) }
        return ParsedLayout(
            uniqueGroups,
            widgets.distinctBy { listOf(it.kind, it.x, it.y, it.width, it.height) },
            staticImages.distinctBy { listOf(it.assetId, it.x, it.y) }
        )
    }

    private fun collectSpriteGroups(
        bytes: ByteArray,
        section: Int,
        imageCount: Int,
        output: MutableList<HwtSpriteGroup>,
        depth: Int
    ) {
        if (depth > 20) return
        val fields = parseProto(bytes) ?: return
        val ids = fields.filter { it.number == 1 && it.bytes != null }
            .mapNotNull { field -> field.bytes!!.toString(Charsets.US_ASCII).takeIf { it.matches(Regex("\\d{3}")) }?.toIntOrNull() }
            .filter { it in 1..imageCount }
            .distinct()
        if (ids.size in 3..15) {
            for (field in fields) {
                val childBytes = field.bytes ?: continue
                val coordinateFields = parseProto(childBytes) ?: continue
                val x = coordinateFields.firstOrNull { it.number == 1 }?.integer ?: continue
                val y = coordinateFields.firstOrNull { it.number == 2 }?.integer ?: continue
                if (x in 0..2000 && y in 0..2000) {
                    val alignment = fields.firstOrNull { it.number == 4 }?.integer?.toInt()?.coerceIn(0, 2) ?: 0
                    output += HwtSpriteGroup(section, ids, x.toInt(), y.toInt(), alignment)
                    break
                }
            }
        }
        fields.forEach { it.bytes?.let { child -> collectSpriteGroups(child, section, imageCount, output, depth + 1) } }
    }

    private fun collectStaticImagePlacements(
        bytes: ByteArray,
        imageCount: Int,
        output: MutableList<HwtImagePlacement>,
        depth: Int
    ) {
        if (depth > 20) return
        val fields = parseProto(bytes) ?: return
        for (reference in fields.filter { it.number == 4 && it.bytes != null }) {
            val imageFields = parseProto(reference.bytes!!) ?: continue
            val assetId = imageFields.firstOrNull { it.number == 1 && it.bytes != null }
                ?.bytes?.toString(Charsets.US_ASCII)?.takeIf { it.matches(Regex("\\d{3}")) }?.toIntOrNull()
                ?.takeIf { it in 1..imageCount } ?: continue
            val coordinateBytes = imageFields.firstOrNull { it.number == 2 && it.bytes != null }?.bytes ?: continue
            val coordinates = parseProto(coordinateBytes) ?: continue
            val x = coordinates.firstOrNull { it.number == 1 }?.integer?.toInt() ?: continue
            val y = coordinates.firstOrNull { it.number == 2 }?.integer?.toInt() ?: continue
            if (x in 0..2000 && y in 0..2000) output += HwtImagePlacement(assetId, x, y)
        }
        fields.forEach { it.bytes?.let { child -> collectStaticImagePlacements(child, imageCount, output, depth + 1) } }
    }

    private fun collectWidgets(bytes: ByteArray, output: MutableList<HwtWidgetPlacement>, depth: Int) {
        if (depth > 20) return
        val fields = parseProto(bytes) ?: return
        val kind = fields.firstOrNull { it.number == 8 }?.integer?.toInt()
        if (kind != null && fields.any { it.number == 1 && it.integer != null }) {
            for (field in fields.filter { it.number == 5 && it.bytes != null }) {
                val rect = parseProto(field.bytes!!) ?: continue
                val values = rect.associate { it.number to it.integer }
                val x = values[1]?.toInt() ?: continue
                val y = values[2]?.toInt() ?: continue
                val width = values[3]?.toInt() ?: continue
                val height = values[4]?.toInt() ?: continue
                if (x >= 0 && y >= 0 && width in 2..2000 && height in 2..2000) {
                    output += HwtWidgetPlacement(kind, x, y, width, height)
                    break
                }
            }
        }
        fields.forEach { it.bytes?.let { child -> collectWidgets(child, output, depth + 1) } }
    }

    private fun parseProto(bytes: ByteArray): List<ProtoField>? = runCatching {
        val fields = ArrayList<ProtoField>()
        var offset = 0
        while (offset < bytes.size) {
            val keyRead = readVarint(bytes, offset)
            val key = keyRead.first
            offset = keyRead.second
            val number = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            require(number > 0)
            when (wire) {
                0 -> {
                    val value = readVarint(bytes, offset)
                    fields += ProtoField(number, wire, integer = value.first)
                    offset = value.second
                }
                1 -> {
                    require(offset + 8 <= bytes.size)
                    fields += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, offset + 8))
                    offset += 8
                }
                2 -> {
                    val sizeRead = readVarint(bytes, offset)
                    val size = sizeRead.first.toInt()
                    offset = sizeRead.second
                    require(size >= 0 && offset + size <= bytes.size)
                    fields += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, offset + size))
                    offset += size
                }
                3 -> {
                    // Huawei's protobuf layout uses legacy groups in a few
                    // nested records. Keep the group's body traversable just
                    // like a length-delimited message so later fields in the
                    // same record are not discarded.
                    val group = readGroup(bytes, offset, number)
                    fields += ProtoField(number, 2, bytes = group.first)
                    offset = group.second
                }
                4 -> {
                    // Some HWT records contain a group terminator in a
                    // sliced nested message. It has no payload; ignore it
                    // instead of rejecting the rest of that record.
                }
                5 -> {
                    require(offset + 4 <= bytes.size)
                    fields += ProtoField(number, wire, bytes = bytes.copyOfRange(offset, offset + 4))
                    offset += 4
                }
                else -> error("Unsupported protobuf wire type $wire")
            }
            require(fields.size <= 100_000)
        }
        fields
    }.getOrNull()

    /** Returns a protobuf group's body and the offset immediately after its matching end tag. */
    private fun readGroup(data: ByteArray, start: Int, groupNumber: Int): Pair<ByteArray, Int> {
        var offset = start
        while (offset < data.size) {
            val tagStart = offset
            val keyRead = readVarint(data, offset)
            val key = keyRead.first
            offset = keyRead.second
            val number = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> offset = readVarint(data, offset).second
                1 -> offset += 8
                2 -> {
                    val lengthRead = readVarint(data, offset)
                    val length = lengthRead.first.toInt()
                    offset = lengthRead.second + length
                }
                3 -> offset = readGroup(data, offset, number).second
                4 -> {
                    require(number == groupNumber) { "Mismatched protobuf group end" }
                    return data.copyOfRange(start, tagStart) to offset
                }
                5 -> offset += 4
                else -> error("Unsupported protobuf group wire type")
            }
            require(offset <= data.size) { "Truncated protobuf group" }
        }
        error("Unterminated protobuf group")
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < data.size && shift < 64) {
            val value = data[offset++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result to offset
            shift += 7
        }
        error("Invalid HWT protobuf varint")
    }

    private fun extractTitle(xml: String): String? {
        val tags = listOf("title", "name", "watchFaceName")
        for (tag in tags) {
            val match = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(xml)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrBlank()) return match.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        }
        return null
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
}
