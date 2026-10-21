package ru.realme.watchport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class ConversionStage { READ_LAYOUT, SELECT_ASSETS, CONVERT_ART, CONVERT_FONTS, BUILD_WIDGETS, PACK_ARCHIVE, VERIFY_OUTPUT, COMPLETE }

internal data class ConvertedFace(
    val iwf: ByteArray,
    val packed: ByteArray,
    val baseName: String,
    val preview: Bitmap,
    val liveFields: List<String>,
    val warnings: List<String>,
    val editableWidgets: List<EditableFaceWidget>
)

internal data class PreparedIwf(
    val iwf: ByteArray,
    val packed: ByteArray,
    val preview: Bitmap?,
    val editableWidgets: List<EditableFaceWidget>
)

internal data class EditableFaceWidget(
    val index: Int,
    val type: String,
    val style: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/** Translates HWT layers, bitmap fonts and live-field geometry into a real GTX10 IWF project. */
internal object IwfConverter {
    internal const val TARGET_WIDTH = 390
    internal const val TARGET_HEIGHT = 450
    private const val ARCHIVE_MAGIC = "iwf\u0000"
    private const val HEADER_SIZE = 8
    private const val ENTRY_SIZE = 40
    private const val NAME_SIZE = 32
    private const val LZ_BLOCK_SIZE = 4096
    private const val FASTLZ_HASH_BITS = 13
    private const val FASTLZ_MIN_MATCH = 3
    private const val FASTLZ_MAX_MATCH = 263
    private const val FASTLZ_LENGTH_ESCAPE = 6
    private const val FASTLZ_MAX_LITERALS = 32
    private const val FASTLZ_MAX_DISTANCE = 8192

    private data class IwfMember(val name: String, val bytes: ByteArray)
    private data class ResolvedGroup(val source: HwtSpriteGroup, val assets: List<HwtImageAsset>) {
        val width: Int get() = assets.maxOfOrNull { it.bitmap.width } ?: 1
        val height: Int get() = assets.maxOfOrNull { it.bitmap.height } ?: 1
        val averageHeight: Int get() = assets.map { it.bitmap.height }.average().roundToInt()
        val averageWidth: Int get() = assets.map { it.bitmap.width }.average().roundToInt()
    }
    private data class TargetGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Accepts a raw GTX10 .iwf archive or the FastLZ block stream used by .iwf.lz. */
    fun prepareForInstall(input: ByteArray): PreparedIwf {
        require(input.isNotEmpty()) { "Selected IWF file is empty" }
        val raw = if (input.size >= 4 && input.copyOfRange(0, 4).contentEquals(byteArrayOf(0x69, 0x77, 0x66, 0))) {
            input
        } else {
            unpackFastLzFile(input)
        }
        val (version, members) = parseArchive(raw)
        require(version == 1) { "Unsupported IWF archive version: $version" }
        val memberMap = members.associateBy { it.name }
        val layoutBytes = memberMap["iwf.json"]?.bytes ?: error("Selected archive has no iwf.json")
        require(memberMap.containsKey("font.json")) { "Selected archive has no font.json" }
        val layout = JSONObject(layoutBytes.toString(StandardCharsets.UTF_8))
        val deviceId = layout.optString("deviceId").trim()
        require(deviceId.isBlank() || deviceId.equals("GTX10", ignoreCase = true)) {
            "This IWF targets a different watch model."
        }
        val background = layout.optString("bkground").takeIf { it.isNotBlank() && it != "null" }
        val preview = layout.optString("preview").takeIf { it.isNotBlank() && it != "null" }
            ?: error("Selected IWF has no preview image reference")
        val backgroundMember = background?.let { memberMap[it] }
        require(background == null || backgroundMember != null) {
            "Selected IWF references a missing background image"
        }
        require(preview in memberMap) { "Selected IWF references a missing preview image" }
        val packed = if (raw === input) {
            packFile(raw) { _, _ -> }.also { encoded ->
                require(unpackFastLzFile(encoded).contentEquals(raw)) {
                    "The IWF compressor produced an invalid transfer archive"
                }
            }
        } else input
        val previewBitmap = backgroundMember?.let { backgroundAsset ->
            runCatching {
                val backgroundBitmap = decodeRawPicture(backgroundAsset.bytes)
                val items = layout.optJSONArray("item") ?: JSONArray()
                renderOutputPreview(backgroundBitmap, items, members)
            }.getOrNull()
        } ?: runCatching { decodeRawPicture(memberMap.getValue(preview).bytes) }.getOrNull()
        return PreparedIwf(raw, packed, previewBitmap, readEditableWidgets(layout))
    }

    /** Move supported fields in an IWF, then rebuild its embedded preview and transfer archive. */
    fun repositionIwf(input: ByteArray, positions: List<EditableFaceWidget>): PreparedIwf {
        require(input.isNotEmpty()) { "Selected IWF file is empty" }
        val raw = if (input.size >= 4 && input.copyOfRange(0, 4).contentEquals(byteArrayOf(0x69, 0x77, 0x66, 0))) {
            input
        } else {
            unpackFastLzFile(input)
        }
        val (version, members) = parseArchive(raw)
        require(version == 1) { "Unsupported IWF archive version: $version" }
        val memberMap = members.associateBy { it.name }
        val layoutMember = memberMap["iwf.json"] ?: error("Selected archive has no iwf.json")
        val layout = JSONObject(layoutMember.bytes.toString(StandardCharsets.UTF_8))
        val items = layout.optJSONArray("item") ?: error("Selected archive has no widget layout")
        positions.forEach { position ->
            val item = items.optJSONObject(position.index) ?: return@forEach
            if (item.optString("type") != position.type) return@forEach
            item.put("x", position.x.coerceIn(0, (TARGET_WIDTH - position.width).coerceAtLeast(0)))
            item.put("y", position.y.coerceIn(0, (TARGET_HEIGHT - position.height).coerceAtLeast(0)))
        }
        val backgroundName = layout.optString("bkground")
        val previewName = layout.optString("preview")
        val background = decodeRawPicture(memberMap[backgroundName]?.bytes ?: error("IWF background is missing"))
        val preview = renderOutputPreview(background, items, members)
        val replacements = mapOf(
            "iwf.json" to layout.toString().toByteArray(StandardCharsets.UTF_8),
            previewName to lz4Rgb565(preview, withAlpha = false)
        )
        val updatedMembers = members.map { member -> IwfMember(member.name, replacements[member.name] ?: member.bytes) }
        val archive = buildArchive(version, updatedMembers)
        val packed = packFile(archive) { _, _ -> }
        validateOutput(archive, packed)
        return PreparedIwf(archive, packed, preview, readEditableWidgets(layout))
    }

    private fun readEditableWidgets(layout: JSONObject): List<EditableFaceWidget> {
        val editableTypes = setOf("hour", "min", "second", "time", "day", "date", "month", "week", "apm", "heartrate", "step", "calorie", "weather", "battery")
        val items = layout.optJSONArray("item") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("type")
            if (type !in editableTypes) return@mapNotNull null
            EditableFaceWidget(
                index = index, type = type, style = item.optInt("style"),
                x = item.optInt("x"), y = item.optInt("y"),
                width = item.optInt("w").coerceAtLeast(1), height = item.optInt("h").coerceAtLeast(1)
            )
        }
    }

    private fun unpackFastLzFile(packed: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var fileOffset = 0
        while (fileOffset < packed.size) {
            require(fileOffset + 4 <= packed.size) { "Truncated FastLZ block header" }
            val blockLength = ((packed[fileOffset].toLong() and 0xff) shl 24) or
                ((packed[fileOffset + 1].toLong() and 0xff) shl 16) or
                ((packed[fileOffset + 2].toLong() and 0xff) shl 8) or
                (packed[fileOffset + 3].toLong() and 0xff)
            fileOffset += 4
            require(blockLength in 1..(packed.size - fileOffset).toLong()) { "Invalid FastLZ block length" }
            val end = fileOffset + blockLength.toInt()
            var decoded = ByteArray(max(4096, blockLength.toInt() * 4))
            var decodedSize = 0
            var firstControl = true

            fun ensureCapacity(required: Int) {
                if (required > decoded.size) decoded = decoded.copyOf(max(required, decoded.size * 2))
            }

            while (fileOffset < end) {
                val rawControl = packed[fileOffset++].toInt() and 0xff
                val control = if (firstControl) rawControl and 0x1f else rawControl
                firstControl = false
                if (control < 32) {
                    val count = control + 1
                    require(fileOffset + count <= end) { "Truncated FastLZ literal run" }
                    ensureCapacity(decodedSize + count)
                    packed.copyInto(decoded, decodedSize, fileOffset, fileOffset + count)
                    decodedSize += count
                    fileOffset += count
                } else {
                    var matchLength = (control ushr 5) - 1
                    if (matchLength == 6) {
                        require(fileOffset < end) { "Truncated FastLZ match length" }
                        matchLength += packed[fileOffset++].toInt() and 0xff
                    }
                    require(fileOffset < end) { "Truncated FastLZ match distance" }
                    val distance = ((control and 0x1f) shl 8) + (packed[fileOffset++].toInt() and 0xff) + 1
                    val copyCount = matchLength + 3
                    require(distance <= decodedSize) { "Invalid FastLZ back-reference" }
                    ensureCapacity(decodedSize + copyCount)
                    repeat(copyCount) {
                        decoded[decodedSize] = decoded[decodedSize - distance]
                        decodedSize++
                    }
                }
            }
            require(fileOffset == end) { "FastLZ block boundary mismatch" }
            output.write(decoded, 0, decodedSize)
        }
        return output.toByteArray()
    }

    fun convert(
        context: Context,
        source: HwtInfo,
        onProgress: (Int, ConversionStage) -> Unit = { _, _ -> }
    ): ConvertedFace {
        onProgress(2, ConversionStage.READ_LAYOUT)
        val baseBytes = context.assets.open("base_gtx10.iwf").use { it.readBytes() }
        val (version, templateMembers) = parseArchive(baseBytes)
        require(version == 1) { "The bundled GTX10 face template has an unsupported version" }
        val templateMap = templateMembers.associateBy { it.name }
        val layoutMember = templateMap["iwf.json"] ?: error("The GTX10 base face has no layout")
        val fontMember = templateMap["font.json"] ?: error("The GTX10 base face has no font map")
        val layout = JSONObject(layoutMember.bytes.toString(StandardCharsets.UTF_8))
        val fontMap = JSONObject(fontMember.bytes.toString(StandardCharsets.UTF_8))
        val backgroundMember = layout.optString("bkground").takeIf { it.isNotBlank() }
            ?: error("The GTX10 base face has no background reference")
        val previewMember = layout.optString("preview").takeIf { it.isNotBlank() }
            ?: error("The GTX10 base face has no preview reference")
        require(templateMap.containsKey(backgroundMember) && templateMap.containsKey(previewMember)) {
            "The GTX10 template is missing its background or preview image"
        }

        onProgress(10, ConversionStage.SELECT_ASSETS)
        val assetById = source.assets.associateBy { it.id }
        val groups = source.spriteGroups.mapNotNull { group ->
            val images = group.assetIds.mapNotNull { assetById[it] }
            if (images.size != group.assetIds.size || images.isEmpty()) null else ResolvedGroup(group, images)
        }.distinctBy { "${it.source.section}:${it.source.x}:${it.source.y}:${it.source.assetIds.joinToString(",")}" }
        val sourceCanvasWidth = source.artwork.width
        val sourceCanvasHeight = source.artwork.height
        val scale = min(TARGET_WIDTH.toFloat() / sourceCanvasWidth, TARGET_HEIGHT.toFloat() / sourceCanvasHeight)
        val offsetX = (TARGET_WIDTH - sourceCanvasWidth * scale) / 2f
        val offsetY = (TARGET_HEIGHT - sourceCanvasHeight * scale) / 2f
        val converterWarnings = ArrayList<String>()
        val geometry = source.widgetPlacements.associateBy { it.kind }
        fun timePlacement(kind: Int): HwtWidgetPlacement? = geometry[kind]?.takeIf {
            it.y < sourceCanvasHeight * 0.70f && it.height < sourceCanvasHeight / 3
        }
        val hourPlacement = timePlacement(28)
        val minutePlacement = timePlacement(30)
        val secondPlacement = timePlacement(29)
        val selectedFonts = selectFonts(groups, sourceCanvasWidth, sourceCanvasHeight, geometry)
        val claimedGroups = linkedSetOf<ResolvedGroup>().apply {
            listOfNotNull(selectedFonts.hour, selectedFonts.minute, selectedFonts.second, selectedFonts.day,
                selectedFonts.month, selectedFonts.week, selectedFonts.apm).forEach(::add)
        }

        fun digitGroupFor(kind: Int): ResolvedGroup? {
            val region = geometry[kind] ?: return null
            val centerX = region.x + region.width / 2
            val targetY = region.y + region.height * 0.68f
            return groups.asSequence().filter { group ->
                group !in claimedGroups && group.source.assetIds.size == 10 &&
                    group.averageHeight in 9..max(100, sourceCanvasHeight / 3) &&
                    group.source.x in (region.x - region.width / 2)..(region.x + region.width * 3 / 2) &&
                    group.source.y in (region.y - region.height / 2)..(region.y + region.height * 3 / 2)
            }.minByOrNull { group ->
                val dx = kotlin.math.abs(group.source.x - centerX)
                val dy = kotlin.math.abs(group.source.y - targetY)
                dx * 3 + dy + sectionDistance(group.source.section, 1) * 12
            }?.also(claimedGroups::add)
        }

        val metricGroups = linkedMapOf(
            "heartrate" to (11 to digitGroupFor(11)),
            "step" to (10 to digitGroupFor(10)),
            "calorie" to (12 to digitGroupFor(12))
        )
        // When a Huawei variant omits widget rectangles, the three lower numeric
        // strips are commonly arranged heart-rate / steps / calories from left to right.
        val unassignedLowerDigits = groups.filter { group ->
            group !in claimedGroups && group.source.assetIds.size == 10 && group.source.y >= sourceCanvasHeight * 0.55f
        }.sortedBy { it.source.x }
        if (unassignedLowerDigits.size >= 3) {
            listOf("heartrate", "step", "calorie").forEachIndexed { index, type ->
                if (metricGroups[type]?.second == null) {
                    val fallbackGroup = unassignedLowerDigits[index]
                    metricGroups[type] = metricGroups.getValue(type).first to fallbackGroup
                    claimedGroups += fallbackGroup
                }
            }
        }

        val additions = ArrayList<IwfMember>()
        val importedFontNames = LinkedHashSet<String>()

        fun addFont(fontName: String, images: List<HwtImageAsset>, memberKeys: List<String>) {
            require(images.size == memberKeys.size)
            images.zip(memberKeys).forEachIndexed { index, (asset, key) ->
                val memberName = "${fontName}_${key}_24bit"
                additions += IwfMember(memberName, lz4Rgb565(asset.bitmap, withAlpha = true))
                if (index % 4 == 0) onProgress(28 + min(28, additions.size / 2), ConversionStage.CONVERT_FONTS)
            }
            importedFontNames += fontName
        }

        fun addBundledFont(fontName: String, keys: List<String>, assetFolder: String) {
            if (fontName in importedFontNames) return
            keys.forEachIndexed { index, key ->
                val path = "$assetFolder/${key}_24bit.png"
                val bitmap = context.assets.open(path).use(BitmapFactory::decodeStream)
                    ?: error("Bundled GTX10 font image is invalid: $path")
                try {
                    additions += IwfMember("${fontName}_${key}_24bit", lz4Rgb565(bitmap, withAlpha = true))
                } finally {
                    bitmap.recycle()
                }
                if (index % 4 == 0) onProgress(28 + min(28, additions.size / 2), ConversionStage.CONVERT_FONTS)
            }
            importedFontNames += fontName
        }

        selectedFonts.hour?.let { addFont("hour", it.assets, (0..9).map(Int::toString)) }
        selectedFonts.minute?.let { addFont("min", it.assets, (0..9).map(Int::toString)) }
        selectedFonts.second?.let { addFont("second", it.assets, (0..9).map(Int::toString)) }
        selectedFonts.day?.let { addFont("day", it.assets, (0..9).map(Int::toString)) }
        selectedFonts.month?.let { addFont("month", it.assets, listOf("en_jan", "en_feb", "en_mar", "en_apr", "en_may", "en_june", "en_july", "en_aug", "en_sept", "en_oct", "en_nov", "en_dec")) }
        selectedFonts.week?.let { addFont("week", it.assets, listOf("en_mon", "en_tue", "en_wed", "en_thur", "en_fri", "en_sat", "en_sun")) }
        selectedFonts.apm?.let { group -> addFont("apm", group.assets.take(2), listOf("en_am", "en_pm")) }
        if (selectedFonts.month == null) {
            addBundledFont("month", listOf("en_jan", "en_feb", "en_mar", "en_apr", "en_may", "en_june", "en_july", "en_aug", "en_sept", "en_oct", "en_nov", "en_dec"), "gtx10/native-fonts/month")
        }
        if (metricGroups.values.any { it.second == null }) {
            addBundledFont("g43", (0..9).map(Int::toString), "gtx10/native-fonts/g43")
        }
        addBundledFont("g921", (0..10).map(Int::toString), "gtx10/metric-fonts/g921")
        addBundledFont("g931", (0..9).map(Int::toString), "gtx10/metric-fonts/g931")
        addBundledFont("g12", (0..9).map(Int::toString), "gtx10/battery-digits")
        addBundledFont("g115", (0..19).map(Int::toString), "gtx10/weather-icons")
        addBundledFont("g125", (0..12).map(Int::toString), "gtx10/native-fonts/g125")
        addBundledFont("g138", (0..12).map(Int::toString), "gtx10/native-fonts/g138")

        if (selectedFonts.month == null) converterWarnings += "The HWT month sprites were not recognized; the GTX10 month font is used."
        if (selectedFonts.week == null) converterWarnings += "The HWT weekday sprites were not recognized; the GTX10 weekday font is used."
        if (selectedFonts.hour == null || selectedFonts.minute == null) {
            converterWarnings += "The HWT time digits were not both recognized; the GTX10 live time font is used."
        }
        if (geometry[10] == null || geometry[11] == null || geometry[12] == null) {
            converterWarnings += "Some health-field positions were inferred from nearby HWT digit groups or the GTX10 layout."
        }

        onProgress(18, ConversionStage.CONVERT_ART)
        val imagePlacements = source.staticImagePlacements.toMutableList()
        fun hasNearbyImage(kind: Int): Boolean {
            val region = geometry[kind] ?: return false
            return imagePlacements.any { placement ->
                placement.x in (region.x - 20)..(region.x + region.width + 20) &&
                    placement.y in (region.y - 20)..(region.y + region.height + 20)
            }
        }
        val batteryGroup = groups.filter { it.source.assetIds.size in 5..7 && it.averageHeight in 18..70 }
            .filter { group -> geometry[26]?.let { region ->
                kotlin.math.abs(group.source.x - (region.x + region.width / 2)) <= region.width &&
                    group.source.y in (region.y - region.height / 3)..(region.y + region.height)
            } == true }.minByOrNull { it.averageHeight }
        if (!hasNearbyImage(26) && batteryGroup != null) {
            val asset = batteryGroup.assets.last()
            val left = when (batteryGroup.source.alignment) {
                1 -> batteryGroup.source.x - asset.bitmap.width / 2
                2 -> batteryGroup.source.x - asset.bitmap.width
                else -> batteryGroup.source.x
            }
            imagePlacements += HwtImagePlacement(asset.id, left.coerceAtLeast(0), batteryGroup.source.y)
        }
        val messageRegion = geometry[18]
        if (messageRegion != null && !hasNearbyImage(18)) {
            val messageIcon = source.assets.filter { asset ->
                val width = asset.bitmap.width
                val height = asset.bitmap.height
                width in 22..64 && height in 18..64 && width.toFloat() / height in 0.78f..1.55f && hasRedBadge(asset.bitmap)
            }.maxByOrNull { messageBadgeScore(it.bitmap) }
            messageIcon?.let { asset ->
                val x = messageRegion.x + (messageRegion.width - asset.bitmap.width) / 2
                val y = messageRegion.y + (messageRegion.height - asset.bitmap.height) / 2
                imagePlacements += HwtImagePlacement(asset.id, x.coerceAtLeast(0), y.coerceAtLeast(0))
            }
        }
        val composedArtwork = composeArtwork(source.artwork, source.assets, imagePlacements)
        val background = renderFit(composedArtwork, TARGET_WIDTH, TARGET_HEIGHT)
        val backgroundBytes = lz4Rgb565(background, withAlpha = false)

        onProgress(62, ConversionStage.BUILD_WIDGETS)
        val targetItems = JSONArray()
        fun alignName(group: ResolvedGroup?, default: String = "left"): String = when (group?.source?.alignment) {
            1 -> "center"
            2 -> "right"
            0 -> "left"
            else -> default
        }
        fun regionGeometry(kind: Int, fallback: TargetGeometry): TargetGeometry = geometry[kind]?.let {
            toTargetGeometry(it.x, it.y, it.width, it.height, scale, offsetX, offsetY)
        } ?: fallback
        fun addSpriteField(
            type: String,
            font: String,
            fontNum: Int,
            sourceGroup: ResolvedGroup?,
            fallback: TargetGeometry,
            sourceKind: Int? = null,
            preferWidgetRegion: Boolean = false
        ) {
            val widgetRegion = sourceKind?.let { geometry[it] }
            val bounds = when {
                preferWidgetRegion && sourceKind == null -> fallback
                preferWidgetRegion && widgetRegion != null && sourceGroup != null &&
                    groupIsNearWidgetRegion(sourceGroup, widgetRegion) -> regionGeometry(sourceKind, fallback)
                else -> sourceGroup?.let { groupGeometry(it, groups, scale, offsetX, offsetY) }
                    ?: sourceKind?.let { regionGeometry(it, fallback) } ?: fallback
            }
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", type); put("isShowAddwh", true)
                put("x", bounds.x); put("y", bounds.y); put("w", bounds.width); put("h", bounds.height)
                put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF")
                put("align", alignName(sourceGroup)); put("style", 0)
                put("font", font); put("fontnum", fontNum)
            })
        }
        fun addMetric(type: String, sourceKind: Int, font: String, fontNum: Int, sourceGroup: ResolvedGroup?, fallback: TargetGeometry) {
            val bounds = regionGeometry(sourceKind, sourceGroup?.let { groupGeometry(it, groups, scale, offsetX, offsetY) } ?: fallback)
            val alignment = alignName(sourceGroup, if (sourceKind == 11) "left" else "center")
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", type); put("isShowAddwh", true)
                put("x", bounds.x); put("y", bounds.y); put("w", bounds.width); put("h", bounds.height)
                put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF"); put("align", alignment)
                put("style", 0); put("font", font); put("fontnum", fontNum)
            })
        }
        fun addWeather(bounds: TargetGeometry, font: String, fontNum: Int, style: Int, alignment: String, extras: JSONObject = JSONObject()) {
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", "weather"); put("isShowAddwh", true)
                put("x", bounds.x); put("y", bounds.y); put("w", bounds.width); put("h", bounds.height)
                put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF"); put("align", alignment)
                put("style", style); put("font", font); put("fontnum", fontNum); put("layout", 0)
                extras.keys().forEach { key -> put(key, extras.get(key)) }
            })
        }

        val hourGroup = selectedFonts.hour
        val minuteGroup = selectedFonts.minute
        if (hourGroup != null && minuteGroup != null) {
            // Some Huawei layouts expose a kind-28 rectangle for a bottom
            // decoration rather than the hour field. When it is outside the
            // time band, anchor minutes to kind 30 and infer the hour slot to
            // its left. This keeps the two large fields from stacking on top
            // of each other.
            val inferredHourBounds = minutePlacement?.let { region ->
                val width = (region.x - region.width / 3).coerceAtLeast(region.width)
                toTargetGeometry(0, region.y, width, region.height, scale, offsetX, offsetY)
            } ?: TargetGeometry(8, 147, 128, 94)
            addSpriteField(
                "hour", "hour", 10, hourGroup, inferredHourBounds,
                hourPlacement?.kind, preferWidgetRegion = true
            )
            addSpriteField(
                "min", "min", 10, minuteGroup, TargetGeometry(150, 147, 118, 94),
                minutePlacement?.kind, preferWidgetRegion = true
            )
            selectedFonts.second?.let {
                addSpriteField(
                    "second", "second", 10, it, TargetGeometry(292, 137, 82, 72),
                    secondPlacement?.kind, preferWidgetRegion = true
                )
            }
        } else {
            val timeBounds = if (hourPlacement != null && minutePlacement != null) {
                val hourRect = hourPlacement; val minRect = minutePlacement
                val left = min(hourRect.x, minRect.x)
                val top = min(hourRect.y, minRect.y)
                val right = max(hourRect.x + hourRect.width, minRect.x + minRect.width)
                val bottom = max(hourRect.y + hourRect.height, minRect.y + minRect.height)
                toTargetGeometry(left, top, right - left, bottom - top, scale, offsetX, offsetY)
            } else TargetGeometry(40, 174, 310, 84)
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", "time"); put("isShowAddwh", true)
                put("x", timeBounds.x); put("y", timeBounds.y); put("w", timeBounds.width); put("h", timeBounds.height)
                put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF"); put("align", "center")
                put("font", "g5564"); put("fontnum", 11)
            })
        }
        if (selectedFonts.day != null) {
            addSpriteField("day", "day", 10, selectedFonts.day, TargetGeometry(90, 48, 42, 30), 25)
        } else {
            val dateBounds = regionGeometry(25, TargetGeometry(90, 48, 42, 30))
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", "date"); put("isShowAddwh", true)
                put("x", dateBounds.x); put("y", dateBounds.y); put("w", dateBounds.width); put("h", dateBounds.height)
                put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF"); put("align", "left")
                put("style", 1); put("font", "g7072"); put("fontnum", 11)
            })
        }
        addSpriteField("month", "month", 12, selectedFonts.month, TargetGeometry(154, 38, 145, 28), 25)
        addSpriteField("week", "week", 7, selectedFonts.week, TargetGeometry(154, 67, 165, 30), 25)
        selectedFonts.apm?.let { addSpriteField("apm", "apm", 2, it, TargetGeometry(26, 54, 48, 25)) }

        metricGroups.forEach { (type, pair) ->
            val fallback = when (type) {
                "heartrate" -> TargetGeometry(34, 276, 90, 30)
                "step" -> TargetGeometry(148, 276, 90, 30)
                else -> TargetGeometry(260, 276, 90, 30)
            }
            val font = if (type == "heartrate") "g921" else "g931"
            val fontNum = if (type == "heartrate") 11 else 10
            addMetric(type, pair.first, font, fontNum, pair.second, fallback)
        }

        val weatherRegion = geometry[21]
        val iconBounds = weatherRegion?.let { region ->
            val iconWidth = (region.width * 0.52f).roundToInt()
            val iconHeight = (region.height * 0.52f).roundToInt()
            toTargetGeometry(
                region.x + (region.width - iconWidth) / 2,
                region.y + (region.height * 0.03f).roundToInt(),
                iconWidth, iconHeight, scale, offsetX, offsetY
            )
        } ?: TargetGeometry(170, 12, 50, 48)
        addWeather(iconBounds, "g115", 20, 1, "center")
        val temperatureBounds = weatherRegion?.let { region ->
            toTargetGeometry(region.x, region.y + (region.height * 0.65f).roundToInt(), region.width,
                max(18, region.height / 3), scale, offsetX, offsetY)
        } ?: TargetGeometry(24, 198, 72, 26)
        addWeather(temperatureBounds, "g125", 13, 2, "center")

        val batteryBounds = regionGeometry(26, TargetGeometry(155, 386, 56, 26))
        targetItems.put(JSONObject().apply {
            put("widget", "custom"); put("type", "battery"); put("isShowAddwh", true)
            put("x", batteryBounds.x); put("y", batteryBounds.y); put("w", batteryBounds.width); put("h", batteryBounds.height)
            put("fgcolor", "0xFFFFFFFF"); put("fgrender", "0xFFFFFFFF"); put("align", "center")
            put("style", 0); put("font", "g12"); put("fontnum", 10)
        })
        geometry[18]?.let { region ->
            val bounds = toTargetGeometry(region.x + region.width - 18, region.y + 2, 16, 16, scale, offsetX, offsetY)
            targetItems.put(JSONObject().apply {
                put("widget", "custom"); put("type", "redpoint"); put("x", bounds.x); put("y", bounds.y)
                put("w", bounds.width); put("h", bounds.height); put("fgcolor", "0xFFFFFFFF")
                put("fgrender", "0x0"); put("align", "left"); put("font", "red_point"); put("fontnum", 7)
            })
        }

        val fontItems = fontMap.optJSONArray("item") ?: JSONArray()
        importedFontNames.forEach { fontName ->
            if ((0 until fontItems.length()).none { fontItems.getJSONObject(it).optString("name") == fontName }) {
                fontItems.put(JSONObject().put("name", fontName).put("bpp", 16).put("format", "png"))
            }
        }
        fontMap.put("item", fontItems)

        val cleanName = source.faceName.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(24).ifBlank { "HWT face" }
        layout.put("name", cleanName)
        layout.put("author", "Realme Watch Port")
        layout.put("description", "HWT port · live data")
        layout.put("deviceId", "GTX10")
        layout.put("compress", "LZ4")
        layout.put("bkground", backgroundMember)
        layout.put("preview", previewMember)
        layout.put("item", targetItems)

        val newMembers = ArrayList<IwfMember>(templateMembers.size + additions.size)
        val addedNames = additions.map { it.name }.toSet()
        templateMembers.forEach { member ->
            if (member.name !in addedNames) newMembers += member
        }
        additions.forEach { addition ->
            val existingIndex = newMembers.indexOfFirst { it.name == addition.name }
            if (existingIndex >= 0) newMembers[existingIndex] = addition else newMembers += addition
        }

        // The HWT cover is only a flattened source thumbnail. Build the preview
        // from the exact converted background, widget geometry and glyph images.
        val preview = renderOutputPreview(background, targetItems, newMembers)
        val previewBytes = lz4Rgb565(preview, withAlpha = false)
        val replacements = mapOf(
            "iwf.json" to layout.toString().toByteArray(StandardCharsets.UTF_8),
            "font.json" to fontMap.toString().toByteArray(StandardCharsets.UTF_8),
            backgroundMember to backgroundBytes,
            previewMember to previewBytes
        )
        newMembers.indices.forEach { index ->
            val replacement = replacements[newMembers[index].name]
            if (replacement != null) newMembers[index] = IwfMember(newMembers[index].name, replacement)
        }

        onProgress(80, ConversionStage.PACK_ARCHIVE)
        val archive = buildArchive(version, newMembers)
        val packed = packFile(archive) { blockIndex, blockCount ->
            onProgress(80 + (9 * (blockIndex + 1) / blockCount), ConversionStage.PACK_ARCHIVE)
        }
        onProgress(92, ConversionStage.VERIFY_OUTPUT)
        validateOutput(archive, packed)
        val liveFields = listOf("time", "date", "month", "week", "weather", "step", "heartrate", "calorie", "battery")
        onProgress(100, ConversionStage.COMPLETE)
        return ConvertedFace(archive, packed, cleanName, preview, liveFields, source.warnings + converterWarnings, readEditableWidgets(layout))
    }

    private data class SelectedFonts(
        val hour: ResolvedGroup?,
        val minute: ResolvedGroup?,
        val second: ResolvedGroup?,
        val day: ResolvedGroup?,
        val month: ResolvedGroup?,
        val week: ResolvedGroup?,
        val apm: ResolvedGroup?
    )

    private fun selectFonts(
        groups: List<ResolvedGroup>,
        sourceWidth: Int,
        sourceHeight: Int,
        widgets: Map<Int, HwtWidgetPlacement>
    ): SelectedFonts {
        val usable = groups.filter { group ->
            val ids = group.source.assetIds
            val heights = group.assets.map { it.bitmap.height }
            val averageHeight = heights.average()
            ids.distinct().size == ids.size &&
                group.source.x in 0 until sourceWidth && group.source.y in 0 until sourceHeight &&
                averageHeight > 0 && heights.maxOrNull()!! - heights.minOrNull()!! <= max(5, (averageHeight * 0.4).roundToInt())
        }
        fun dateGroupScore(group: ResolvedGroup): Int {
            val region = widgets[25] ?: return sectionDistance(group.source.section, 2) * 100_000 + group.source.y
            val targetX = region.x + region.width / 2
            val targetY = region.y + region.height / 2
            val dx = kotlin.math.abs(group.source.x - targetX)
            val dy = kotlin.math.abs(group.source.y - targetY)
            return dx + dy * 2 + sectionDistance(group.source.section, 2) * 25
        }
        val month = usable.filter { it.source.assetIds.size == 12 && it.averageHeight in 12..100 && it.averageWidth > it.averageHeight }
            .minByOrNull(::dateGroupScore)
        val week = usable.filter { it.source.assetIds.size == 7 && it.averageHeight in 12..100 && it.averageWidth > it.averageHeight }
            .minByOrNull(::dateGroupScore)
        val largeDigits = usable.filter {
            it.source.assetIds.size == 10 && it.averageHeight >= max(45, sourceHeight / 9) && it.averageWidth >= 20
        }
        // Huawei layouts can put health digit strips in the same protobuf
        // section as time digits. Exclude groups anchored in the explicit
        // health rectangles from hour/minute pairing.
        val healthRegions = listOfNotNull(widgets[10], widgets[11], widgets[12])
        fun belongsToHealthRegion(group: ResolvedGroup): Boolean {
            val marginX = max(12, group.averageWidth / 2)
            val marginY = max(12, group.averageHeight / 2)
            return healthRegions.any { region ->
                group.source.x in (region.x - marginX)..(region.x + region.width + marginX) &&
                    group.source.y in (region.y - marginY)..(region.y + region.height + marginY)
            }
        }
        val timeDigitCandidates = largeDigits.filterNot(::belongsToHealthRegion)
        data class TimePair(val first: ResolvedGroup, val second: ResolvedGroup, val score: Long)
        val timePairs = ArrayList<TimePair>()
        for (firstIndex in timeDigitCandidates.indices) {
            for (secondIndex in firstIndex + 1 until timeDigitCandidates.size) {
                val first = timeDigitCandidates[firstIndex]
                val second = timeDigitCandidates[secondIndex]
                val sameGlyphSet = first.source.assetIds.toSet() == second.source.assetIds.toSet()
                val heightDifference = kotlin.math.abs(first.averageHeight - second.averageHeight)
                val heightLimit = max(6, max(first.averageHeight, second.averageHeight) / 4)
                val xDifference = kotlin.math.abs(first.source.x - second.source.x)
                val yDifference = kotlin.math.abs(first.source.y - second.source.y)
                val minimumSeparation = max(4, (min(first.averageWidth, second.averageWidth) * 0.35f).roundToInt())
                val maxCoordinateDistance = max(xDifference, yDifference)
                // Huawei faces place the two live time groups in a row, a column,
                // or diagonally. Only requiring a shared baseline dropped the
                // bitmap digits from several built-in style families.
                if (heightDifference > heightLimit || maxCoordinateDistance < minimumSeparation) continue
                val distanceSquared = xDifference.toLong() * xDifference + yDifference.toLong() * yDifference
                val maximumPairDistance = max(sourceWidth, sourceHeight) * 0.50f
                if (distanceSquared > maximumPairDistance * maximumPairDistance) continue
                val hourRect = widgets[28]?.takeIf { isUsableTimePlacement(it, sourceHeight) }
                val minuteRect = widgets[30]?.takeIf { isUsableTimePlacement(it, sourceHeight) }
                val placementScore = if (hourRect != null && minuteRect != null) {
                    fun anchorDistance(group: ResolvedGroup, rect: HwtWidgetPlacement): Int {
                        val expectedX = rect.x + rect.width / 2
                        val expectedY = rect.y + rect.height * 2 / 3
                        return kotlin.math.abs(group.source.x - expectedX) +
                            kotlin.math.abs(group.source.y - expectedY) * 2
                    }
                    minOf(
                        anchorDistance(first, hourRect) + anchorDistance(second, minuteRect),
                        anchorDistance(second, hourRect) + anchorDistance(first, minuteRect)
                    ).toLong()
                } else if (minuteRect != null) {
                    fun anchorDistance(group: ResolvedGroup, rect: HwtWidgetPlacement): Int {
                        val expectedX = rect.x + rect.width / 2
                        val expectedY = rect.y + rect.height * 2 / 3
                        return kotlin.math.abs(group.source.x - expectedX) +
                            kotlin.math.abs(group.source.y - expectedY) * 2
                    }
                    val firstDistance = anchorDistance(first, minuteRect)
                    val secondDistance = anchorDistance(second, minuteRect)
                    val minuteGroup = if (firstDistance <= secondDistance) first else second
                    val hourGroup = if (minuteGroup === first) second else first
                    val expectedHourX = (minuteRect.x - minuteRect.width * 0.9f).roundToInt().coerceAtLeast(0)
                    (min(firstDistance, secondDistance) * 2 +
                        kotlin.math.abs(hourGroup.source.x - expectedHourX) +
                        kotlin.math.abs(hourGroup.source.y - minuteGroup.source.y) * 2).toLong()
                } else 0L
                val score = sectionDistance(first.source.section, 3) * 100_000L +
                    (if (sameGlyphSet) 0L else 40_000L) +
                    (if (first.source.section == second.source.section) 0L else 25_000L) +
                    heightDifference * 1_000L + maxCoordinateDistance * 100L + distanceSquared + placementScore * 8L
                timePairs += TimePair(first, second, score)
            }
        }
        val timePair = timePairs.minByOrNull { it.score }
        val hour: ResolvedGroup?
        val minute: ResolvedGroup?
        if (timePair == null) {
            hour = null
            minute = null
        } else {
            val hourRect = widgets[28]?.takeIf { isUsableTimePlacement(it, sourceHeight) }
            val minuteRect = widgets[30]?.takeIf { isUsableTimePlacement(it, sourceHeight) }
            val firstComesFirst = if (hourRect != null && minuteRect != null) {
                fun anchorDistance(group: ResolvedGroup, rect: HwtWidgetPlacement): Long {
                    val expectedX = rect.x + rect.width / 2
                    val expectedY = rect.y + rect.height * 2 / 3
                    return kotlin.math.abs(group.source.x - expectedX).toLong() +
                        kotlin.math.abs(group.source.y - expectedY).toLong() * 2L
                }
                anchorDistance(timePair.first, hourRect) + anchorDistance(timePair.second, minuteRect) <=
                    anchorDistance(timePair.second, hourRect) + anchorDistance(timePair.first, minuteRect)
            } else if (minuteRect != null) {
                fun anchorDistance(group: ResolvedGroup): Int {
                    val expectedX = minuteRect.x + minuteRect.width / 2
                    val expectedY = minuteRect.y + minuteRect.height * 2 / 3
                    return kotlin.math.abs(group.source.x - expectedX) +
                        kotlin.math.abs(group.source.y - expectedY) * 2
                }
                // The group nearest kind 30 is minutes; the remaining group is
                // hours, even if the sprite anchors are not listed left to right.
                anchorDistance(timePair.first) > anchorDistance(timePair.second)
            } else {
                val dx = kotlin.math.abs(timePair.first.source.x - timePair.second.source.x)
                val dy = kotlin.math.abs(timePair.first.source.y - timePair.second.source.y)
                if (dx >= dy) timePair.first.source.x <= timePair.second.source.x
                else timePair.first.source.y <= timePair.second.source.y
            }
            hour = if (firstComesFirst) timePair.first else timePair.second
            minute = if (firstComesFirst) timePair.second else timePair.first
        }
        val timeGroups = listOfNotNull(hour, minute).toSet()
        val second = if (minute == null) null else usable.filter {
            it.source.assetIds.size == 10 && it !in timeGroups &&
                it.source.section == minute.source.section &&
                it.averageHeight < minute.averageHeight * 0.75 && it.averageHeight >= 12 &&
                listOfNotNull(hour, minute).minOf { timeGroup ->
                    max(kotlin.math.abs(it.source.x - timeGroup.source.x), kotlin.math.abs(it.source.y - timeGroup.source.y))
                } <= max(100, minute.averageHeight * 2)
        }.minByOrNull { candidate ->
            listOfNotNull(hour, minute).minOf { timeGroup ->
                max(kotlin.math.abs(candidate.source.x - timeGroup.source.x), kotlin.math.abs(candidate.source.y - timeGroup.source.y))
            } * 100 + candidate.averageHeight
        }
        val dayCandidates = usable.filter {
            it.source.assetIds.size == 10 && it.averageHeight in 15..max(60, sourceHeight / 5) &&
                it.averageWidth in 10..max(100, sourceWidth / 2) && it.source.y < sourceHeight * 0.70f
                && it !in timeGroups && it != second
        }
        val day = dayCandidates.minByOrNull { candidate ->
            val dateRect = widgets[25]
            if (dateRect == null) sectionDistance(candidate.source.section, 2) * 100_000 + candidate.source.y
            else {
                val dx = kotlin.math.abs(candidate.source.x - (dateRect.x + dateRect.width / 2))
                val dy = kotlin.math.abs(candidate.source.y - (dateRect.y + dateRect.height / 2))
                dx + dy * 2 + sectionDistance(candidate.source.section, 2) * 25
            }
        }
        val apm = usable.filter {
            it.source.section >= 2 && it.source.assetIds.size == 3 && it.averageHeight in 12..60 && it.averageWidth > it.averageHeight
        }.minByOrNull { sectionDistance(it.source.section, 3) * 100_000 + it.source.y }
        return SelectedFonts(hour, minute, second, day, month, week, apm)
    }

    private fun sectionDistance(section: Int, preferred: Int): Int = kotlin.math.abs(section - preferred)

    private fun isUsableTimePlacement(placement: HwtWidgetPlacement, sourceHeight: Int): Boolean =
        placement.y < sourceHeight * 0.70f && placement.height < sourceHeight / 3

    private fun groupIsNearWidgetRegion(group: ResolvedGroup, region: HwtWidgetPlacement): Boolean {
        fun axisDistance(point: Int, start: Int, size: Int): Int = when {
            point < start -> start - point
            point > start + size -> point - (start + size)
            else -> 0
        }
        val dx = axisDistance(group.source.x, region.x, region.width)
        val dy = axisDistance(group.source.y, region.y, region.height)
        return dx <= max(24, group.averageWidth) && dy <= max(24, group.averageHeight)
    }

    private fun hasRedBadge(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var red = 0
        var light = 0
        pixels.forEach { pixel ->
            if ((pixel ushr 24) < 96) return@forEach
            val r = (pixel ushr 16) and 0xff
            val g = (pixel ushr 8) and 0xff
            val b = pixel and 0xff
            if (r > 170 && g < 110 && b < 110) red++
            if (r > 175 && g > 175 && b > 175) light++
        }
        return red >= 2 && light >= 8
    }

    private fun messageBadgeScore(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var red = 0
        var light = 0
        pixels.forEach { pixel ->
            if ((pixel ushr 24) < 96) return@forEach
            val r = (pixel ushr 16) and 0xff
            val g = (pixel ushr 8) and 0xff
            val b = pixel and 0xff
            if (r > 170 && g < 110 && b < 110) red++
            if (r > 175 && g > 175 && b > 175) light++
        }
        val shape = 100 - kotlin.math.abs(bitmap.width - bitmap.height) * 2
        return red * 10 + light + shape
    }

    private fun readItems(items: JSONArray?): List<JSONObject> = buildList {
        if (items == null) return@buildList
        for (index in 0 until items.length()) add(items.getJSONObject(index))
    }

    private fun groupGeometry(
        group: ResolvedGroup,
        allGroups: List<ResolvedGroup>,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): TargetGeometry {
        val hasTenDigits = group.source.assetIds.size == 10
        val restrictedTens = if (hasTenDigits) allGroups.filter { candidate ->
            candidate.source.assetIds.size in 3..6 &&
                candidate.averageHeight >= group.averageHeight * 0.75 &&
                candidate.averageHeight <= group.averageHeight * 1.25 &&
                kotlin.math.abs(candidate.source.y - group.source.y) <= max(3, group.averageHeight / 12) &&
                candidate.source.x < group.source.x &&
                group.source.x - candidate.source.x <= candidate.width + group.width
        }.minByOrNull { kotlin.math.abs(group.source.x - it.source.x - it.averageWidth) } else null
        val anchorX = restrictedTens?.source?.x ?: group.source.x
        val startY = min(group.source.y, restrictedTens?.source?.y ?: group.source.y)
        val width = when {
            restrictedTens != null -> group.source.x - anchorX + group.width
            hasTenDigits -> group.width * 2
            else -> group.width
        }
        val height = max(group.height, restrictedTens?.height ?: 1)
        val startX = when (group.source.alignment) {
            1 -> anchorX - width / 2
            2 -> anchorX - width
            else -> anchorX
        }
        return toTargetGeometry(startX, startY, width, height, scale, offsetX, offsetY)
    }

    private fun toTargetGeometry(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ): TargetGeometry {
        val targetX = (offsetX + x * scale).roundToInt().coerceIn(0, TARGET_WIDTH - 1)
        val targetY = (offsetY + y * scale).roundToInt().coerceIn(0, TARGET_HEIGHT - 1)
        val targetWidth = max(1, (width * scale).roundToInt()).coerceAtMost(TARGET_WIDTH - targetX)
        val targetHeight = max(1, (height * scale).roundToInt()).coerceAtMost(TARGET_HEIGHT - targetY)
        return TargetGeometry(targetX, targetY, targetWidth, targetHeight)
    }

    private fun renderFit(source: Bitmap, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = (source.width * scale).roundToInt()
        val scaledHeight = (source.height * scale).roundToInt()
        val left = (width - scaledWidth) / 2
        val top = (height - scaledHeight) / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, Rect(left, top, left + scaledWidth, top + scaledHeight), paint)
        return output
    }

    /** Renders a representative watch screen from the generated IWF layers and bitmap fonts. */
    private fun renderOutputPreview(background: Bitmap, items: JSONArray, members: List<IwfMember>): Bitmap {
        val face = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(face)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(background, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))

        val memberMap = members.associateBy { it.name }
        val glyphs = HashMap<String, Bitmap?>()
        fun glyph(font: String, key: String): Bitmap? {
            val name = "${font}_${key}_24bit"
            if (glyphs.containsKey(name)) return glyphs[name]
            val bitmap = memberMap[name]?.bytes?.let { bytes -> runCatching { decodeRawPicture(bytes) }.getOrNull() }
            glyphs[name] = bitmap
            return bitmap
        }

        val samples = mapOf(
            "hour" to listOf("0", "9"), "min" to listOf("3", "0"), "second" to listOf("0", "0"),
            "day" to listOf("0", "5"), "date" to listOf("0", "5"), "month" to listOf("en_sept"),
            "week" to listOf("en_tue"), "apm" to listOf("en_am"), "heartrate" to listOf("7", "2"),
            "step" to listOf("1", "2", "3", "4", "5"), "calorie" to listOf("3", "2", "8"),
            "battery" to listOf("1", "0", "0")
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        fun drawGlyphs(item: JSONObject, keys: List<String>) {
            val font = item.optString("font")
            val sprites = keys.mapNotNull { key -> glyph(font, key) }
            if (sprites.isEmpty()) return
            val x = item.optInt("x", 0).toFloat()
            val y = item.optInt("y", 0).toFloat()
            val width = item.optInt("w", 0).toFloat().coerceAtLeast(1f)
            val height = item.optInt("h", 0).toFloat().coerceAtLeast(1f)
            val naturalWidth = sprites.sumOf { it.width.toDouble() }.toFloat()
            val naturalHeight = sprites.maxOf { it.height }.toFloat()
            val scale = min(width / naturalWidth, height / naturalHeight)
            val drawWidth = naturalWidth * scale
            val drawHeight = naturalHeight * scale
            val left = when (item.optString("align", "left")) {
                "center" -> x + (width - drawWidth) / 2f
                "right" -> x + width - drawWidth
                else -> x
            }
            val top = y + (height - drawHeight) / 2f
            var cursor = left
            sprites.forEach { sprite ->
                val spriteWidth = sprite.width * scale
                val spriteHeight = sprite.height * scale
                val spriteTop = top + (drawHeight - spriteHeight) / 2f
                canvas.drawBitmap(sprite, null, android.graphics.RectF(cursor, spriteTop, cursor + spriteWidth, spriteTop + spriteHeight), paint)
                cursor += spriteWidth
            }
        }

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val type = item.optString("type")
            when (type) {
                "weather" -> {
                    when (item.optInt("style", 0)) {
                        1 -> drawGlyphs(item, listOf("2"))
                        2 -> {
                            val keys = if (item.has("futureday")) listOf("1", "4") else listOf("10", "2", "0", "11")
                            drawGlyphs(item, keys)
                        }
                    }
                }
                "time" -> {
                    val box = android.graphics.RectF(
                        item.optInt("x", 0).toFloat(), item.optInt("y", 0).toFloat(),
                        (item.optInt("x", 0) + item.optInt("w", 0)).toFloat(),
                        (item.optInt("y", 0) + item.optInt("h", 0)).toFloat()
                    )
                    val mid = box.centerX()
                    val leftItem = JSONObject().put("font", item.optString("font")).put("x", box.left).put("y", box.top)
                        .put("w", box.width() * 0.43f).put("h", box.height()).put("align", "center")
                    val rightItem = JSONObject().put("font", item.optString("font")).put("x", box.left + box.width() * 0.57f).put("y", box.top)
                        .put("w", box.width() * 0.43f).put("h", box.height()).put("align", "center")
                    drawGlyphs(leftItem, listOf("0", "9"))
                    drawGlyphs(rightItem, listOf("3", "0"))
                    paint.color = Color.WHITE
                    paint.style = Paint.Style.FILL
                    val dotRadius = max(1.5f, box.height() * 0.035f)
                    canvas.drawCircle(mid, box.centerY() - dotRadius * 2f, dotRadius, paint)
                    canvas.drawCircle(mid, box.centerY() + dotRadius * 2f, dotRadius, paint)
                }
                "redpoint" -> {
                    val dot = glyph(item.optString("font"), "0")
                    if (dot != null) drawGlyphs(item, listOf("0"))
                }
                else -> samples[type]?.let { drawGlyphs(item, it) }
            }
        }

        glyphs.values.filterNotNull().distinct().forEach { it.recycle() }
        val preview = renderFit(face, 214, 246)
        face.recycle()
        return preview
    }

    private fun composeArtwork(
        base: Bitmap,
        assets: List<HwtImageAsset>,
        placements: List<HwtImagePlacement>
    ): Bitmap {
        val canvasBitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(base, 0f, 0f, paint)
        val assetsById = assets.associateBy { it.id }
        placements.forEach { placement ->
            val image = assetsById[placement.assetId]?.bitmap ?: return@forEach
            if (placement.x >= base.width || placement.y >= base.height) return@forEach
            canvas.drawBitmap(image, placement.x.toFloat(), placement.y.toFloat(), paint)
        }
        return canvasBitmap
    }

    private fun decodeRawPicture(bytes: ByteArray): Bitmap {
        require(bytes.size >= 16 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x41, 0x57, 0))) {
            "IWF preview is not a supported RAW image"
        }
        val width = u16(bytes, 4)
        val height = u16(bytes, 6)
        require(width in 1..1200 && height in 1..1200) { "Invalid IWF preview dimensions" }
        val hasAlpha = bytes[9] == 0x66.toByte()
        val bytesPerPixel = if (hasAlpha) 3 else 2
        val expected = width.toLong() * height * bytesPerPixel
        require(expected <= 16L * 1024 * 1024) { "IWF preview is too large" }
        val pixels = lz4DecompressBlock(bytes.copyOfRange(16, bytes.size), expected.toInt())
        val argb = IntArray(width * height)
        for (index in argb.indices) {
            val offset = index * bytesPerPixel
            val rgb565 = (pixels[offset].toInt() and 0xff) or ((pixels[offset + 1].toInt() and 0xff) shl 8)
            val red5 = (rgb565 ushr 11) and 0x1f
            val green6 = (rgb565 ushr 5) and 0x3f
            val blue5 = rgb565 and 0x1f
            val red = (red5 shl 3) or (red5 ushr 2)
            val green = (green6 shl 2) or (green6 ushr 4)
            val blue = (blue5 shl 3) or (blue5 ushr 2)
            val alpha = if (hasAlpha) pixels[offset + 2].toInt() and 0xff else 0xff
            argb[index] = Color.argb(alpha, red, green, blue)
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun lz4DecompressBlock(data: ByteArray, expectedSize: Int): ByteArray {
        val output = ByteArray(expectedSize)
        var inputOffset = 0
        var outputOffset = 0

        fun readExtendedLength(initial: Int): Int {
            var length = initial
            if (initial == 15) {
                while (true) {
                    require(inputOffset < data.size) { "Truncated IWF LZ4 length" }
                    val extra = data[inputOffset++].toInt() and 0xff
                    length = Math.addExact(length, extra)
                    if (extra != 0xff) break
                }
            }
            return length
        }

        while (inputOffset < data.size) {
            val token = data[inputOffset++].toInt() and 0xff
            val literalLength = readExtendedLength(token ushr 4)
            require(inputOffset + literalLength <= data.size && outputOffset + literalLength <= output.size) {
                "Invalid IWF LZ4 literal run"
            }
            data.copyInto(output, outputOffset, inputOffset, inputOffset + literalLength)
            inputOffset += literalLength
            outputOffset += literalLength
            if (inputOffset == data.size) break

            require(inputOffset + 2 <= data.size) { "Truncated IWF LZ4 match" }
            val distance = (data[inputOffset].toInt() and 0xff) or ((data[inputOffset + 1].toInt() and 0xff) shl 8)
            inputOffset += 2
            require(distance in 1..outputOffset) { "Invalid IWF LZ4 back-reference" }
            val matchLength = readExtendedLength(token and 0x0f) + 4
            require(outputOffset + matchLength <= output.size) { "IWF LZ4 output exceeds image dimensions" }
            repeat(matchLength) {
                output[outputOffset] = output[outputOffset - distance]
                outputOffset++
            }
        }
        require(outputOffset == expectedSize && inputOffset == data.size) { "IWF picture is truncated" }
        return output
    }

    /** Writes a GTX10 RAW picture: LZ4 of little-endian RGB565, optionally followed by A8 alpha. */
    private fun lz4Rgb565(bitmap: Bitmap, withAlpha: Boolean): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val raw = ByteArrayOutputStream(pixels.size * if (withAlpha) 3 else 2)
        for (pixel in pixels) {
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            val value = ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)
            raw.write(value and 0xff)
            raw.write((value ushr 8) and 0xff)
            if (withAlpha) raw.write((pixel ushr 24) and 0xff)
        }
        val compressed = lz4CompressBlock(raw.toByteArray())
        val output = ByteArrayOutputStream(16 + compressed.size)
        output.write(byteArrayOf(0x52, 0x41, 0x57, 0))
        writeU16(output, bitmap.width)
        writeU16(output, bitmap.height)
        output.write(0x85)
        output.write(if (withAlpha) 0x66 else 0)
        repeat(6) { output.write(0) }
        output.write(compressed)
        return output.toByteArray()
    }

    /** Standard LZ4 block encoder. Leave the final five bytes literal as required by the format. */
    private fun lz4CompressBlock(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Cannot encode an empty picture" }
        val output = ByteArrayOutputStream(data.size + data.size / 255 + 8)
        val table = IntArray(1 shl 16) { -1 }
        fun sequence(at: Int): Int =
            (data[at].toInt() and 0xff) or
                ((data[at + 1].toInt() and 0xff) shl 8) or
                ((data[at + 2].toInt() and 0xff) shl 16) or
                ((data[at + 3].toInt() and 0xff) shl 24)
        fun hash(value: Int): Int = ((value.toLong() * 2_654_435_761L) ushr 16).toInt() and 0xffff
        fun writeExtension(length: Int) {
            var remaining = length - 15
            while (remaining >= 255) {
                output.write(255)
                remaining -= 255
            }
            output.write(remaining)
        }

        val matchSearchLimit = data.size - 12
        val matchEndLimit = data.size - 5
        var anchor = 0
        var position = 0
        while (position <= matchSearchLimit) {
            val hash = hash(sequence(position))
            val candidate = table[hash]
            table[hash] = position
            if (candidate < 0 || position - candidate > 0xffff || sequence(candidate) != sequence(position)) {
                position++
                continue
            }

            var matchLength = 4
            while (position + matchLength < matchEndLimit && data[candidate + matchLength] == data[position + matchLength]) {
                matchLength++
            }
            val literalLength = position - anchor
            val token = (min(literalLength, 15) shl 4) or min(matchLength - 4, 15)
            output.write(token)
            if (literalLength >= 15) writeExtension(literalLength)
            output.write(data, anchor, literalLength)
            val distance = position - candidate
            output.write(distance and 0xff)
            output.write((distance ushr 8) and 0xff)
            if (matchLength - 4 >= 15) writeExtension(matchLength - 4)

            val nextPosition = position + matchLength
            for (updateAt in position + 1 until min(nextPosition, data.size - 3)) {
                table[hash(sequence(updateAt))] = updateAt
            }
            position = nextPosition
            anchor = position
        }

        val literalLength = data.size - anchor
        output.write(min(literalLength, 15) shl 4)
        if (literalLength >= 15) writeExtension(literalLength)
        output.write(data, anchor, literalLength)
        return output.toByteArray()
    }

    private fun packFile(data: ByteArray, onBlock: (Int, Int) -> Unit): ByteArray {
        val result = ByteArrayOutputStream(data.size + data.size / 32 + 128)
        val blockCount = (data.size + LZ_BLOCK_SIZE - 1) / LZ_BLOCK_SIZE
        var offset = 0
        var blockIndex = 0
        while (offset < data.size) {
            val size = min(LZ_BLOCK_SIZE, data.size - offset)
            val block = packFastLzBlock(data, offset, size)
            writeU32BigEndian(result, block.size)
            result.write(block)
            offset += size
            onBlock(blockIndex++, blockCount)
        }
        return result.toByteArray()
    }

    /** FastLZ level-2 block encoder, compatible with the GTX10 transfer stream. */
    private fun packFastLzBlock(data: ByteArray, blockStart: Int, length: Int): ByteArray {
        val output = ByteArrayOutputStream(length)
        val recent = IntArray(1 shl FASTLZ_HASH_BITS) { -1 }
        var anchor = 0
        var position = 0

        while (true) {
            if (position + FASTLZ_MIN_MATCH <= length) {
                val slot = fastLzHash(data, blockStart + position)
                val candidate = recent[slot]
                recent[slot] = position
                val distance = position - candidate
                val matchLength = if (candidate >= 0 && distance <= FASTLZ_MAX_DISTANCE) {
                    fastLzMatchLength(data, blockStart, candidate, position, length)
                } else {
                    0
                }

                if (matchLength < FASTLZ_MIN_MATCH) {
                    position++
                } else {
                    writeFastLzLiterals(output, data, blockStart + anchor, position - anchor)
                    writeFastLzMatch(output, distance, matchLength)
                    var updateAt = position + 1
                    val matchEnd = position + matchLength
                    while (updateAt + FASTLZ_MIN_MATCH <= matchEnd) {
                        recent[fastLzHash(data, blockStart + updateAt)] = updateAt
                        updateAt++
                    }
                    position = matchEnd
                    anchor = position
                }
            } else {
                writeFastLzLiterals(output, data, blockStart + anchor, length - anchor)
                val block = output.toByteArray()
                // The encoder starts every block with a literal run, so the
                // first control byte can carry FastLZ level 2 in its top bits.
                block[0] = (block[0].toInt() or 0x20).toByte()
                return block
            }
        }
    }

    private fun writeFastLzLiterals(output: ByteArrayOutputStream, data: ByteArray, offset: Int, count: Int) {
        var written = 0
        while (written < count) {
            val run = min(FASTLZ_MAX_LITERALS, count - written)
            output.write(run - 1)
            output.write(data, offset + written, run)
            written += run
        }
    }

    private fun writeFastLzMatch(output: ByteArrayOutputStream, distance: Int, length: Int) {
        val encodedDistance = distance - 1
        val encodedLength = length - FASTLZ_MIN_MATCH
        if (encodedLength < FASTLZ_LENGTH_ESCAPE) {
            output.write(((encodedLength + 1) shl 5) or (encodedDistance ushr 8))
        } else {
            output.write(0xe0 or (encodedDistance ushr 8))
            output.write(encodedLength - FASTLZ_LENGTH_ESCAPE)
        }
        output.write(encodedDistance and 0xff)
    }

    private fun fastLzHash(data: ByteArray, offset: Int): Int {
        val sequence = ((data[offset].toInt() and 0xff) shl 16) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset + 2].toInt() and 0xff)
        return (-1640531535 * sequence) ushr (32 - FASTLZ_HASH_BITS)
    }

    private fun fastLzMatchLength(data: ByteArray, blockStart: Int, candidate: Int, position: Int, end: Int): Int {
        val limit = min(FASTLZ_MAX_MATCH, end - position)
        var length = 0
        while (length < limit && data[blockStart + candidate + length] == data[blockStart + position + length]) {
            length++
        }
        return length
    }

    private fun parseArchive(data: ByteArray): Pair<Int, List<IwfMember>> {
        require(data.size >= HEADER_SIZE && data.copyOfRange(0, 4).contentEquals(byteArrayOf(0x69, 0x77, 0x66, 0))) {
            "Invalid GTX10 base archive"
        }
        val version = u16(data, 4)
        val count = u16(data, 6)
        require(count in 1..4096 && HEADER_SIZE + count * ENTRY_SIZE <= data.size) { "Invalid IWF table" }
        val names = HashSet<String>()
        val members = ArrayList<IwfMember>(count)
        for (index in 0 until count) {
            val record = HEADER_SIZE + index * ENTRY_SIZE
            var nameEnd = record
            while (nameEnd < record + NAME_SIZE && data[nameEnd] != 0.toByte()) nameEnd++
            val name = data.copyOfRange(record, nameEnd).toString(StandardCharsets.UTF_8)
            val offset = u32(data, record + NAME_SIZE).toInt()
            val length = u32(data, record + NAME_SIZE + 4).toInt()
            require(names.add(name)) { "Duplicate IWF member: $name" }
            require(offset >= HEADER_SIZE + count * ENTRY_SIZE && length >= 0 && offset.toLong() + length <= data.size) {
                "Invalid IWF member: $name"
            }
            members += IwfMember(name, data.copyOfRange(offset, offset + length))
        }
        return version to members
    }

    private fun buildArchive(version: Int, members: List<IwfMember>): ByteArray {
        require(members.size <= 0xffff)
        require(members.map { it.name }.distinct().size == members.size) { "Duplicate IWF member name" }
        val tableSize = HEADER_SIZE + members.size * ENTRY_SIZE
        val output = ByteArrayOutputStream(tableSize + members.sumOf { it.bytes.size })
        output.write(byteArrayOf(0x69, 0x77, 0x66, 0))
        writeU16(output, version)
        writeU16(output, members.size)
        var bodyOffset = tableSize
        for (member in members) {
            val name = member.name.toByteArray(StandardCharsets.UTF_8)
            require(name.size < NAME_SIZE) { "IWF member name is too long: ${member.name}" }
            output.write(name)
            repeat(NAME_SIZE - name.size) { output.write(0) }
            writeU32(output, bodyOffset.toLong())
            writeU32(output, member.bytes.size.toLong())
            bodyOffset += member.bytes.size
        }
        members.forEach { output.write(it.bytes) }
        return output.toByteArray()
    }

    /** Validate the generated face before exposing it to the user. */
    private fun validateOutput(archive: ByteArray, packed: ByteArray) {
        val (version, members) = parseArchive(archive)
        require(version == 1 && members.size >= 55) { "Generated IWF archive header is invalid" }
        val memberMap = members.associateBy { it.name }
        val layoutBytes = memberMap["iwf.json"]?.bytes ?: error("Generated IWF has no iwf.json")
        val fontBytes = memberMap["font.json"]?.bytes ?: error("Generated IWF has no font.json")
        val layout = JSONObject(layoutBytes.toString(StandardCharsets.UTF_8))
        val fontMap = JSONObject(fontBytes.toString(StandardCharsets.UTF_8))
        require(memberMap.containsKey(layout.optString("bkground")) && memberMap.containsKey(layout.optString("preview"))) {
            "Generated IWF references a missing background or preview image"
        }
        val fonts = buildSet {
            val items = fontMap.optJSONArray("item") ?: error("Generated font.json has no item list")
            for (index in 0 until items.length()) add(items.getJSONObject(index).optString("name"))
        }
        val liveTypes = HashSet<String>()
        val items = layout.optJSONArray("item") ?: error("Generated iwf.json has no item list")
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val type = item.optString("type")
            liveTypes += type
            val font = item.optString("font")
            if (font.isNotBlank()) {
                require(font in fonts) { "Generated widget $type references an undeclared font $font" }
                val prefix = "${font}_"
                require(memberMap.keys.any { it.startsWith(prefix) }) { "Generated widget $type has no bitmap font images" }
                val requiredKeys = when (font) {
                    "hour", "min", "second", "day" -> (0..9).map(Int::toString)
                    "month" -> listOf("en_jan", "en_feb", "en_mar", "en_apr", "en_may", "en_june", "en_july", "en_aug", "en_sept", "en_oct", "en_nov", "en_dec")
                    "week" -> listOf("en_mon", "en_tue", "en_wed", "en_thur", "en_fri", "en_sat", "en_sun")
                    "apm" -> listOf("en_am", "en_pm")
                    else -> emptyList()
                }
                requiredKeys.forEach { key ->
                    require("${font}_${key}_24bit" in memberMap) {
                        "Generated widget $type is missing bitmap font strip ${font}_${key}_24bit"
                    }
                }
            }
        }
        val required = setOf("heartrate", "weather", "step", "calorie", "battery")
        require(liveTypes.containsAll(required)) { "Generated IWF is missing live health or weather widgets" }
        require("time" in liveTypes || ("hour" in liveTypes && "min" in liveTypes)) {
            "Generated IWF is missing a live time widget"
        }

        members.forEach { member ->
            if (member.bytes.size >= 16 && member.bytes[0] == 'R'.code.toByte() && member.bytes[1] == 'A'.code.toByte() && member.bytes[2] == 'W'.code.toByte()) {
                validateRawPicture(member.name, member.bytes)
            }
        }
        require(unpackFastLzFile(packed).contentEquals(archive)) { "Generated .iwf.lz does not unpack to the .iwf archive" }
    }

    private fun validateRawPicture(name: String, raw: ByteArray) {
        val width = u16(raw, 4)
        val height = u16(raw, 6)
        require(width in 1..1000 && height in 1..1000 && raw[8] == 0x85.toByte()) { "Invalid picture header: $name" }
        val alpha = raw[9] == 0x66.toByte()
        require(raw[9] == 0.toByte() || alpha) { "Invalid picture flags: $name" }
        val expected = width.toLong() * height * if (alpha) 3L else 2L
        require(expected <= Int.MAX_VALUE) { "Picture is too large: $name" }
        val decoded = decodeLz4Block(raw, 16)
        require(decoded.size.toLong() == expected) { "Picture data length does not match header: $name" }
    }

    private fun decodeLz4Block(data: ByteArray, start: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var input = start
        while (input < data.size) {
            val token = data[input++].toInt() and 0xff
            var literals = token ushr 4
            if (literals == 15) {
                var extra: Int
                do { require(input < data.size); extra = data[input++].toInt() and 0xff; literals += extra } while (extra == 255)
            }
            require(input + literals <= data.size)
            output.write(data, input, literals)
            input += literals
            if (input == data.size) break
            require(input + 2 <= data.size)
            val distance = (data[input].toInt() and 0xff) or ((data[input + 1].toInt() and 0xff) shl 8)
            input += 2
            require(distance > 0 && distance <= output.size())
            var matchLength = (token and 0x0f) + 4
            if ((token and 0x0f) == 15) {
                var extra: Int
                do { require(input < data.size); extra = data[input++].toInt() and 0xff; matchLength += extra } while (extra == 255)
            }
            val decoded = output.toByteArray()
            val from = decoded.size - distance
            repeat(matchLength) { output.write(decoded[from + it % distance].toInt()) }
        }
        return output.toByteArray()
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)

    private fun writeU16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff); output.write((value ushr 8) and 0xff)
    }

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private fun writeU32BigEndian(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xff); output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff); output.write(value and 0xff)
    }
}
