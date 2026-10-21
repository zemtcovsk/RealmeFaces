package ru.realme.watchport

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.provider.Settings
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val BrandYellow = Color(0xFFFFD400)
private val PageYellow = Color(0xFFFFF8D9)
private val Ink = Color(0xFF161616)
private val MutedInk = Color(0xFF625B3C)
private val CardWhite = Color(0xFFFFFEF8)
private val ErrorRed = Color(0xFFB3261E)

private data class FaceVariant(
    val languageCode: String,
    val languageName: Int,
    val iwfAsset: String,
    val coverAsset: String
)

private data class StoreFace(
    val id: String,
    val title: Int,
    val description: Int,
    val badge: Int,
    val variants: List<FaceVariant>,
    val type: WatchFaceType
)

private data class ReadyIwfFile(
    val name: String,
    val title: String,
    val prepared: PreparedIwf
)

private enum class WatchFaceType(val label: Int) {
    DIGITAL(R.string.filter_digital),
    ANALOG(R.string.filter_analog),
    EXTENDED(R.string.filter_extended)
}

private fun englishFace(iwf: String, cover: String) = FaceVariant("en", R.string.language_english, iwf, cover)

private fun iwfDisplayTitle(fileName: String): String {
    val lowerName = fileName.lowercase(Locale.ROOT)
    val suffixLength = when {
        lowerName.endsWith(".iwf.lz") -> 7
        lowerName.endsWith(".iwf") -> 4
        else -> 0
    }
    return if (suffixLength == 0) fileName else fileName.dropLast(suffixLength).ifBlank { fileName }
}

private val storeFaces = listOf(
    StoreFace("ww102", R.string.face_ww102_name, R.string.face_ww102_description, R.string.badge_ported, listOf(FaceVariant("ru", R.string.language_russian, "ww102_ru.iwf", "ww102_ru.png")), WatchFaceType.EXTENDED),
    StoreFace("sdwlsn", R.string.face_sdwlsn_name, R.string.face_sdwlsn_description, R.string.badge_ported, listOf(FaceVariant("ru", R.string.language_russian, "sdwlsn445_ru.iwf", "sdwlsn445_ru.png")), WatchFaceType.EXTENDED),
    StoreFace("clock50013", R.string.face_clock50013_name, R.string.face_clock50013_description, R.string.badge_ported, listOf(
        englishFace("clock50013.iwf", "clock50013.png"),
        FaceVariant("ru", R.string.language_russian, "clock50013_ru.iwf", "clock50013_ru.png")
    ), WatchFaceType.ANALOG),
    StoreFace("hmp_b_fit", R.string.face_hmp_b_fit_name, R.string.face_hmp_b_fit_description, R.string.badge_ported, listOf(englishFace("hmp_b_fit.iwf", "hmp_b_fit.png")), WatchFaceType.ANALOG),
    StoreFace("russian_w12", R.string.face_w12_name, R.string.face_w12_description, R.string.badge_russian, listOf(
        FaceVariant("ru", R.string.language_russian, "russian_w12.iwf", "russian_w12.png")
    ), WatchFaceType.DIGITAL),
    StoreFace("ens4_vm", R.string.face_ens4_name, R.string.face_ens4_description, R.string.badge_ported, listOf(
        FaceVariant("ru", R.string.language_russian, "ens4_vm.iwf", "ens4_vm.png")
    ), WatchFaceType.EXTENDED),
    StoreFace("bgr4_vm", R.string.face_bgr4_name, R.string.face_bgr4_description, R.string.badge_ported, listOf(
        FaceVariant("ru", R.string.language_russian, "bgr4_vm.iwf", "bgr4_vm.png")
    ), WatchFaceType.EXTENDED),
    StoreFace("factory1316", R.string.face_factory1316_name, R.string.face_factory1316_description, R.string.badge_factory, listOf(
        englishFace("factory_1316_en.iwf", "factory_1316.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1316_ru.iwf", "factory_1316_ru.png")
    ), WatchFaceType.ANALOG),
    StoreFace("factory1317", R.string.face_factory1317_name, R.string.face_factory1317_description, R.string.badge_factory, listOf(
        englishFace("factory_1317_en.iwf", "factory_1317.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1317_ru.iwf", "factory_1317_ru.png")
    ), WatchFaceType.EXTENDED),
    StoreFace("factory1318", R.string.face_factory1318_name, R.string.face_factory1318_description, R.string.badge_factory, listOf(
        englishFace("factory_1318_en.iwf", "factory_1318.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1318_ru.iwf", "factory_1318_ru.png")
    ), WatchFaceType.EXTENDED),
    StoreFace("factory1319", R.string.face_factory1319_name, R.string.face_factory1319_description, R.string.badge_factory, listOf(
        englishFace("factory_1319_en.iwf", "factory_1319.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1319_ru.iwf", "factory_1319_ru.png")
    ), WatchFaceType.DIGITAL),
    StoreFace("factory1320", R.string.face_factory1320_name, R.string.face_factory1320_description, R.string.badge_factory, listOf(
        englishFace("factory_1320_en.iwf", "factory_1320.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1320_ru.iwf", "factory_1320_ru.png")
    ), WatchFaceType.DIGITAL),
    StoreFace("factory1321", R.string.face_factory1321_name, R.string.face_factory1321_description, R.string.badge_factory, listOf(
        englishFace("factory_1321_en.iwf", "factory_1321.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1321_ru.iwf", "factory_1321_ru.png")
    ), WatchFaceType.DIGITAL),
    StoreFace("factory1323", R.string.face_factory1323_name, R.string.face_factory1323_description, R.string.badge_factory, listOf(englishFace("factory_1323_en.iwf", "factory_1323.png")), WatchFaceType.ANALOG),
    StoreFace("factory1324", R.string.face_factory1324_name, R.string.face_factory1324_description, R.string.badge_factory, listOf(
        englishFace("factory_1324_en.iwf", "factory_1324.png"),
        FaceVariant("ru", R.string.language_russian, "factory_1324_ru.iwf", "factory_1324_ru.png")
    ), WatchFaceType.EXTENDED)
)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val language = newBase.getSharedPreferences("watchfaces_settings", Context.MODE_PRIVATE).getString("language", null)
        if (language == null) {
            super.attachBaseContext(newBase)
        } else {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(255, 212, 0)
        window.navigationBarColor = android.graphics.Color.rgb(255, 248, 217)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { WatchfacesApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchfacesApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ble = remember(context) { VeryFitBleClient(context) }
    DisposableEffect(ble) { onDispose { ble.close() } }
    val uploadActive = ble.installing.value
    DisposableEffect(uploadActive) {
        val window = (context as? MainActivity)?.window
        if (uploadActive) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedFaceId by remember { mutableStateOf<String?>(null) }
    var selectedFaceLanguage by remember { mutableStateOf<String?>(null) }
    var selectedIwfFile by remember { mutableStateOf<ReadyIwfFile?>(null) }
    var readingIwfFile by remember { mutableStateOf(false) }
    var iwfFileError by remember { mutableStateOf<String?>(null) }
    var installingSelected by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var faceQuery by remember { mutableStateOf("") }
    var faceTypeFilter by remember { mutableStateOf<WatchFaceType?>(null) }
    var faceLanguageFilter by remember { mutableStateOf<String?>(null) }
    var showFaceFilters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val selectedFace = storeFaces.firstOrNull { it.id == selectedFaceId }
    val appLanguage = context.resources.configuration.locales[0]?.language ?: "en"
    val selectedVariant = selectedFace?.let { face ->
        val preferredLanguage = selectedFaceLanguage ?: appLanguage
        face.variants.firstOrNull { it.languageCode == preferredLanguage } ?: face.variants.first()
    }

    val bluetoothPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled == true) ble.startScan()
        else ble.error.value = context.getString(R.string.error_bluetooth_disabled)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (bluetoothPermissions.all { grants[it] == true }) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled == true) ble.startScan()
            else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else ble.error.value = context.getString(R.string.error_bluetooth_permission)
    }

    val iwfDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedIwfFile = null
            iwfFileError = null
            ble.error.value = null
            readingIwfFile = true
            scope.launch {
                try {
                    val readyFile = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "watchface.iwf"
                        val lowerName = name.lowercase(Locale.ROOT)
                        require(lowerName.endsWith(".iwf") || lowerName.endsWith(".iwf.lz")) {
                            context.getString(R.string.error_iwf_extension)
                        }
                        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error(context.getString(R.string.error_invalid_iwf))
                        ReadyIwfFile(name, iwfDisplayTitle(name), IwfConverter.prepareForInstall(source))
                    }
                    selectedIwfFile = readyFile
                } catch (error: Exception) {
                    iwfFileError = error.localizedMessage ?: context.getString(R.string.error_invalid_iwf)
                } finally {
                    readingIwfFile = false
                }
            }
        }
    }

    fun beginWatchScan() {
        message = null
        ble.error.value = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != true
        ) {
            ble.error.value = context.getString(R.string.location_disabled)
            return
        }
        val granted = bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            permissionRequest.launch(bluetoothPermissions)
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled == true) ble.startScan()
        else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    fun installFace(face: StoreFace, variant: FaceVariant) {
        message = null
        if (!ble.connected.value) {
            message = context.getString(R.string.connect_to_install)
            selectedTab = 1
            selectedFaceId = null
            return
        }
        if (ble.installing.value || installingSelected) return
        ble.error.value = null
        installingSelected = true
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val source = context.assets.open("faces/${variant.iwfAsset}").use { it.readBytes() }
                    IwfConverter.prepareForInstall(source)
                }
                // Catalog faces keep the pre-file-picker transfer behavior.
                ble.installBuiltIn(prepared.iwf, prepared.packed, context.getString(face.title))
            } catch (error: Exception) {
                ble.error.value = error.localizedMessage ?: context.getString(R.string.error_invalid_iwf)
            } finally {
                installingSelected = false
            }
        }
    }

    val colors = lightColorScheme(
        primary = BrandYellow,
        onPrimary = Ink,
        secondary = Color(0xFFE8BE00),
        onSecondary = Ink,
        background = PageYellow,
        onBackground = Ink,
        surface = CardWhite,
        onSurface = Ink,
        surfaceVariant = Color(0xFFFFEEA1),
        onSurfaceVariant = MutedInk,
        outlineVariant = Color(0xFFE0D7AE)
    )

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(
                                    when {
                                        showSettings -> R.string.settings_title
                                        selectedFace != null -> selectedFace.title
                                        selectedTab == 2 -> R.string.install_tab
                                        else -> R.string.store_brand
                                    }
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = if (selectedFace == null) 16.sp else 18.sp,
                                color = Ink
                            )
                            Text(
                                text = stringResource(
                                    when {
                                        showSettings -> R.string.settings_subtitle
                                        selectedFace != null -> selectedFace.badge
                                        selectedTab == 2 -> R.string.install_tab_subtitle
                                        else -> R.string.store_subtitle
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedInk
                            )
                        }
                    },
                    navigationIcon = {
                        if (showSettings) {
                            TextButton(onClick = { showSettings = false }) {
                                Text("‹", fontSize = 34.sp, color = Ink, textAlign = TextAlign.Center)
                            }
                        } else if (selectedFace != null) {
                            TextButton(onClick = { selectedFaceId = null; selectedFaceLanguage = null; message = null }) {
                                Text("‹", fontSize = 34.sp, color = Ink, textAlign = TextAlign.Center)
                            }
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            (context as? MainActivity)?.let { activity ->
                                val nextLanguage = when (appLanguage) {
                                    "ru" -> "en"
                                    "en" -> "zh-CN"
                                    else -> "ru"
                                }
                                activity.getSharedPreferences("watchfaces_settings", Context.MODE_PRIVATE)
                                    .edit().putString("language", nextLanguage).apply()
                                activity.recreate()
                            }
                        }) {
                            Text(
                                when (appLanguage) {
                                    "ru" -> "EN"
                                    "en" -> "中文"
                                    else -> "RU"
                                },
                                color = Ink,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = {
                                showSettings = true
                                showLicense = false
                                showAbout = false
                            },
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.settings_button_description)
                            }
                        ) {
                            SettingsGearIcon(Modifier.size(22.dp), color = Ink)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandYellow)
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier.fillMaxWidth().background(PageYellow),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uploadActive || installingSelected) {
                        Card(
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = CardWhite),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (uploadActive) ble.status.value else stringResource(R.string.reading_iwf),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Ink,
                                    maxLines = 1
                                )
                                if (uploadActive) {
                                    LinearProgressIndicator(
                                        progress = ble.progress.intValue / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = BrandYellow,
                                        trackColor = Color(0xFFECE4C7)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = BrandYellow,
                                        trackColor = Color(0xFFECE4C7)
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        NavigationBar(
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                                .border(1.dp, Color(0xFFE6D78D), RoundedCornerShape(28.dp)),
                            containerColor = CardWhite,
                            tonalElevation = 4.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1; selectedFaceId = null; selectedFaceLanguage = null; message = null },
                                icon = { StoreTabIcon(isDevice = true, selected = selectedTab == 1) },
                                label = { Text(stringResource(R.string.device_tab)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink, selectedTextColor = Ink, indicatorColor = BrandYellow,
                                    unselectedIconColor = MutedInk, unselectedTextColor = MutedInk
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0; selectedFaceId = null; selectedFaceLanguage = null; message = null },
                                icon = { StoreTabIcon(isDevice = false, selected = selectedTab == 0) },
                                label = { Text(stringResource(R.string.store_tab)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink, selectedTextColor = Ink, indicatorColor = BrandYellow,
                                    unselectedIconColor = MutedInk, unselectedTextColor = MutedInk
                                )
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2; selectedFaceId = null; selectedFaceLanguage = null; message = null },
                                icon = { InstallTabIcon(selected = selectedTab == 2) },
                                label = { Text(stringResource(R.string.install_tab)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink, selectedTextColor = Ink, indicatorColor = BrandYellow,
                                    unselectedIconColor = MutedInk, unselectedTextColor = MutedInk
                                )
                            )
                        }
                    }
                }
            },
            containerColor = PageYellow
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (showSettings) {
                    SettingsPage(
                        onLicenseClick = { showLicense = true },
                        onAboutClick = { showAbout = true }
                    )
                } else if (selectedTab == 1) {
                    DevicePage(
                        ble = ble,
                        onConnect = ::beginWatchScan,
                        onSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        }
                    )
                } else if (selectedTab == 2) {
                    InstallAnyPage(
                        ble = ble,
                        file = selectedIwfFile,
                        readingFile = readingIwfFile,
                        fileError = iwfFileError,
                        onChooseFile = { iwfDocumentPicker.launch(arrayOf("*/*")) },
                        onInstall = {
                            val file = selectedIwfFile ?: return@InstallAnyPage
                            if (!ble.connected.value) {
                                selectedTab = 1
                                return@InstallAnyPage
                            }
                            ble.error.value = null
                            // User-selected files use the explicit finalize + apply route.
                            ble.installSelectedFile(file.prepared.iwf, file.prepared.packed, file.title, file.name)
                        },
                        onOpenDevice = { selectedTab = 1; selectedFaceId = null; selectedFaceLanguage = null }
                    )
                } else if (selectedFace == null) {
                    StoreLanding(
                        ble = ble,
                        message = message,
                        query = faceQuery,
                        typeFilter = faceTypeFilter,
                        languageFilter = faceLanguageFilter,
                        onQueryChange = { faceQuery = it },
                        onOpenFilters = { showFaceFilters = true },
                        onOpenDevice = { selectedTab = 1 },
                        onOpenFace = { face, language -> selectedFaceId = face.id; selectedFaceLanguage = language }
                    )
                } else {
                    FaceDetails(
                        face = selectedFace,
                        variant = requireNotNull(selectedVariant),
                        ble = ble,
                        installingSelected = installingSelected,
                        message = message,
                        onInstall = { installFace(selectedFace, requireNotNull(selectedVariant)) },
                        onLanguageSelected = { selectedFaceLanguage = it },
                        onOpenDevice = { selectedTab = 1; selectedFaceId = null; selectedFaceLanguage = null }
                    )
                }
            }
        }
        if (showFaceFilters) {
            WatchFaceFiltersDialog(
                typeFilter = faceTypeFilter,
                languageFilter = faceLanguageFilter,
                onDismiss = { showFaceFilters = false },
                onApply = { type, language ->
                    faceTypeFilter = type
                    faceLanguageFilter = language
                    showFaceFilters = false
                }
            )
        }
        if (showLicense) {
            AlertDialog(
                onDismissRequest = { showLicense = false },
                title = { Text(stringResource(R.string.settings_license), color = Ink, fontWeight = FontWeight.Bold) },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.license_agreement_text), color = Ink)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLicense = false }) {
                        Text(stringResource(R.string.settings_close), color = Ink)
                    }
                },
                containerColor = CardWhite
            )
        }
        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text(stringResource(R.string.settings_about), color = Ink, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.app_name), color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("${stringResource(R.string.app_version_label)}: ${stringResource(R.string.app_version)}", color = Ink)
                        Text("${stringResource(R.string.app_developer_label)}: ${stringResource(R.string.app_developer)}", color = Ink)
                        if (ble.connected.value) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.connected_watch_details), color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("${stringResource(R.string.connected_watch_name)}: ${ble.connectedWatchName.value.orEmpty()}", color = Ink)
                            Text("${stringResource(R.string.watch_bt_version)}: ${stringResource(R.string.watch_bt_protocol)}", color = Ink)
                            Text("${stringResource(R.string.watch_number)}: ${ble.connectedWatchNumber.value.orEmpty()}", color = Ink)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAbout = false }) {
                        Text(stringResource(R.string.settings_close), color = Ink)
                    }
                },
                containerColor = CardWhite
            )
        }
    }
}

@Composable
private fun SettingsPage(onLicenseClick: () -> Unit, onAboutClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsOption(
            title = stringResource(R.string.settings_license),
            subtitle = stringResource(R.string.settings_license_summary),
            onClick = onLicenseClick
        )
        SettingsOption(
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_about_summary),
            onClick = onAboutClick
        )
    }
}

@Composable
private fun SettingsOption(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = CardWhite,
        border = BorderStroke(1.dp, Color(0xFFE6D78D))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = MutedInk, fontSize = 28.sp)
        }
    }
}

@Composable
private fun StoreLanding(
    ble: VeryFitBleClient,
    message: String?,
    query: String,
    typeFilter: WatchFaceType?,
    languageFilter: String?,
    onQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenFace: (StoreFace, String?) -> Unit
) {
    val context = LocalContext.current
    val matchingFaces = storeFaces.filter { face ->
        val matchesQuery = query.isBlank() || context.getString(face.title).contains(query, ignoreCase = true) ||
            context.getString(face.description).contains(query, ignoreCase = true) ||
            context.getString(face.badge).contains(query, ignoreCase = true) ||
            context.getString(face.type.label).contains(query, ignoreCase = true)
        val matchesType = typeFilter == null || face.type == typeFilter
        val matchesLanguage = languageFilter == null || face.variants.any { it.languageCode == languageFilter }
        matchesQuery && matchesType && matchesLanguage
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = BrandYellow)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.store_heading), fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(stringResource(R.string.store_description), style = MaterialTheme.typography.bodyMedium, color = Ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CardWhite.copy(alpha = 0.85f)) {
                        Text(stringResource(R.string.available_faces, matchingFaces.size), modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.SemiBold, color = Ink)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.store_format_badge), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                }
            }
        }
        ConnectionPill(ble = ble, onClick = onOpenDevice)
        if (message != null) NoticeCard(message, isError = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.search_faces), color = MutedInk) },
                leadingIcon = { SearchMark() }
            )
            Surface(
                onClick = onOpenFilters,
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = BrandYellow,
                contentColor = Ink,
                border = BorderStroke(1.dp, Ink.copy(alpha = 0.15f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    FilterMark()
                }
            }
        }
        if (matchingFaces.isEmpty()) {
            Text(
                stringResource(R.string.no_faces_found),
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                textAlign = TextAlign.Center,
                color = MutedInk
            )
        } else {
            val visibleTypes = typeFilter?.let(::listOf) ?: WatchFaceType.values().toList()
            visibleTypes.forEach { type ->
                val groupFaces = matchingFaces.filter { it.type == type }
                if (groupFaces.isNotEmpty()) {
                    Text(stringResource(type.label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                    groupFaces.chunked(2).forEach { rowFaces ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowFaces.forEach { face ->
                                StoreFaceCard(
                                    face = face,
                                    preferredLanguage = languageFilter,
                                    modifier = Modifier.weight(1f),
                                    onOpen = { onOpenFace(face, languageFilter) }
                                )
                            }
                            if (rowFaces.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreFaceCard(face: StoreFace, preferredLanguage: String?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val deviceLanguage = context.resources.configuration.locales[0]?.language ?: "en"
    val previewVariant = face.variants.firstOrNull { it.languageCode == (preferredLanguage ?: deviceLanguage) } ?: face.variants.first()
    Card(
        modifier = modifier.clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BrandYellow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier.fillMaxWidth().height(122.dp).clip(RoundedCornerShape(14.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                WatchFaceMockup(
                    coverAsset = previewVariant.coverAsset,
                    contentDescription = stringResource(face.title),
                    modifier = Modifier.height(122.dp)
                )
            }
            Text(stringResource(face.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, lineHeight = 18.sp, color = Ink, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(30.dp), color = Color.White) {
                    Text(stringResource(face.badge), modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
                }
                val availableLanguages = face.variants.map { it.languageCode.uppercase(Locale.ROOT) }.distinct().joinToString(" · ")
                Surface(shape = RoundedCornerShape(30.dp), color = Color.White) {
                    Text(availableLanguages, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun WatchFaceMockup(
    coverAsset: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    previewBitmap: Bitmap? = null
) {
    val context = LocalContext.current
    val cover = remember(coverAsset, previewBitmap) {
        previewBitmap?.asImageBitmap() ?: coverAsset?.let { asset ->
            runCatching { context.assets.open("faces/$asset").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val shell = remember {
        runCatching { context.assets.open("watch_shell.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    Box(modifier.aspectRatio(236f / 384f), contentAlignment = Alignment.Center) {
        if (shell != null) {
            Image(shell, contentDescription = contentDescription, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / 236f
            val sy = size.height / 384f
            val screen = Path().apply {
                // Follow the watch glass: tighter upper shoulders, rounded lower corners.
                moveTo(92f * sx, 80f * sy)
                lineTo(129f * sx, 80f * sy)
                cubicTo(165f * sx, 80f * sy, 203f * sx, 98.5f * sy, 203f * sx, 130f * sy)
                lineTo(203f * sx, 245f * sy)
                cubicTo(203f * sx, 272.6f * sy, 171.4f * sx, 295f * sy, 149f * sx, 295f * sy)
                lineTo(72f * sx, 295f * sy)
                cubicTo(49.6f * sx, 295f * sy, 18f * sx, 272.6f * sy, 18f * sx, 245f * sy)
                lineTo(18f * sx, 130f * sy)
                cubicTo(18f * sx, 98.5f * sy, 56f * sx, 80f * sy, 92f * sx, 80f * sy)
                close()
            }
            clipPath(screen) {
                drawRect(Color.Black)
                if (cover != null) {
                    drawImage(
                        image = cover,
                        dstOffset = IntOffset((18f * sx).toInt(), (80f * sy).toInt()),
                        dstSize = IntSize((185f * sx).toInt(), (215f * sy).toInt()),
                        filterQuality = FilterQuality.High
                    )
                }
            }
        }
    }
}
@Composable
private fun SearchMark() {
    Canvas(Modifier.size(20.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .42f)
        drawCircle(MutedInk, radius = size.width * .27f, center = center, style = Stroke(strokeWidth))
        drawLine(MutedInk, androidx.compose.ui.geometry.Offset(size.width * .62f, size.height * .62f), androidx.compose.ui.geometry.Offset(size.width * .88f, size.height * .88f), strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
private fun FilterMark() {
    Canvas(Modifier.size(23.dp)) {
        val width = size.width
        val height = size.height
        val line = 2.dp.toPx()
        val funnel = Path().apply {
            moveTo(width * 0.15f, height * 0.2f)
            lineTo(width * 0.85f, height * 0.2f)
            lineTo(width * 0.59f, height * 0.5f)
            lineTo(width * 0.59f, height * 0.77f)
            lineTo(width * 0.41f, height * 0.88f)
            lineTo(width * 0.41f, height * 0.5f)
            close()
        }
        drawPath(funnel, Ink, style = Stroke(width = line, cap = StrokeCap.Round))
    }
}

@Composable
private fun WatchFaceFiltersDialog(
    typeFilter: WatchFaceType?,
    languageFilter: String?,
    onDismiss: () -> Unit,
    onApply: (WatchFaceType?, String?) -> Unit
) {
    var draftType by remember(typeFilter) { mutableStateOf(typeFilter) }
    var draftLanguage by remember(languageFilter) { mutableStateOf(languageFilter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_title), fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.filter_type), fontWeight = FontWeight.SemiBold, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChoice(stringResource(R.string.filter_all), draftType == null, Modifier.weight(1f)) { draftType = null }
                    FilterChoice(stringResource(R.string.filter_digital), draftType == WatchFaceType.DIGITAL, Modifier.weight(1f)) { draftType = WatchFaceType.DIGITAL }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChoice(stringResource(R.string.filter_analog), draftType == WatchFaceType.ANALOG, Modifier.weight(1f)) { draftType = WatchFaceType.ANALOG }
                    FilterChoice(stringResource(R.string.filter_extended), draftType == WatchFaceType.EXTENDED, Modifier.weight(1f)) { draftType = WatchFaceType.EXTENDED }
                }
                Text(stringResource(R.string.filter_extended_help), style = MaterialTheme.typography.bodySmall, color = MutedInk)
                Text(stringResource(R.string.filter_language), fontWeight = FontWeight.SemiBold, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChoice(stringResource(R.string.filter_all), draftLanguage == null, Modifier.weight(1f)) { draftLanguage = null }
                    FilterChoice(stringResource(R.string.language_russian), draftLanguage == "ru", Modifier.weight(1f)) { draftLanguage = "ru" }
                    FilterChoice(stringResource(R.string.language_english), draftLanguage == "en", Modifier.weight(1f)) { draftLanguage = "en" }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { draftType = null; draftLanguage = null }) {
                        Text(stringResource(R.string.filter_reset), color = MutedInk)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(draftType, draftLanguage) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
            ) { Text(stringResource(R.string.filter_apply), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.filter_cancel), color = MutedInk) }
        },
        containerColor = CardWhite
    )
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) BrandYellow else CardWhite,
        contentColor = Ink,
        border = BorderStroke(1.dp, if (selected) Ink.copy(alpha = 0.24f) else Color(0xFFE0D7AE))
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = Ink,
            maxLines = 1
        )
    }
}

@Composable
private fun FaceDetails(
    face: StoreFace,
    variant: FaceVariant,
    ble: VeryFitBleClient,
    installingSelected: Boolean,
    message: String?,
    onInstall: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onOpenDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(22.dp)).background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                WatchFaceMockup(
                    coverAsset = variant.coverAsset,
                    contentDescription = stringResource(face.title),
                    modifier = Modifier.height(240.dp)
                )
            }
            Surface(shape = RoundedCornerShape(30.dp), color = if (face.badge == R.string.badge_factory) BrandYellow else Color(0xFFF0EBD6)) {
                Text(stringResource(face.badge), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Text(stringResource(face.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Ink)
            Text(stringResource(face.description), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MutedInk)
            if (face.variants.size > 1) {
                Text(stringResource(R.string.face_language), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    face.variants.forEach { option ->
                        Surface(
                            modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { onLanguageSelected(option.languageCode) },
                            shape = RoundedCornerShape(24.dp),
                            color = if (variant.languageCode == option.languageCode) BrandYellow else Color(0xFFF0EBD6)
                        ) {
                            Text(stringResource(option.languageName), modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                    }
                }
            }
            Text(stringResource(R.string.install_ready), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MutedInk)
            if (ble.installing.value) {
                LinearProgressIndicator(progress = ble.progress.intValue / 100f, modifier = Modifier.fillMaxWidth(), color = BrandYellow, trackColor = Color(0xFFECE4C7))
                Text(stringResource(R.string.transfer_progress, ble.progress.intValue), style = MaterialTheme.typography.labelMedium, color = Ink)
            }
            if (ble.error.value != null) NoticeCard(ble.error.value.orEmpty(), isError = true)
            if (message != null) NoticeCard(message, isError = true)
            if (ble.connected.value) {
                Button(
                    onClick = onInstall,
                    enabled = !installingSelected && !ble.installing.value,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
                ) {
                    Text(if (installingSelected) stringResource(R.string.reading_iwf) else stringResource(R.string.install_face), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onOpenDevice,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
                ) { Text(stringResource(R.string.connect_to_install), fontWeight = FontWeight.Bold) }
            }
            if (ble.installing.value) Text(ble.status.value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MutedInk)
        }
    }
}

@Composable
private fun InstallAnyPage(
    ble: VeryFitBleClient,
    file: ReadyIwfFile?,
    readingFile: Boolean,
    fileError: String?,
    onChooseFile: () -> Unit,
    onInstall: () -> Unit,
    onOpenDevice: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionPill(ble, onOpenDevice)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = BrandYellow)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                InstallTabIcon(selected = true)
                Text(stringResource(R.string.install_any_heading), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                Text(stringResource(R.string.install_any_description), style = MaterialTheme.typography.bodyMedium, color = Ink, textAlign = TextAlign.Center)
                Button(
                    onClick = onChooseFile,
                    enabled = !readingFile && !ble.installing.value,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardWhite, contentColor = Ink)
                ) {
                    Text(stringResource(if (file == null) R.string.select_iwf_file else R.string.change_iwf_file), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (readingFile) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BrandYellow, trackColor = Color(0xFFECE4C7))
            Text(stringResource(R.string.reading_iwf), style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        if (fileError != null) NoticeCard(fileError, isError = true)
        if (ble.error.value != null) NoticeCard(ble.error.value.orEmpty(), isError = true)
        file?.let { selected ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    selected.prepared.preview?.let { preview ->
                        Box(
                            Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp)).background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            WatchFaceMockup(
                                coverAsset = null,
                                contentDescription = selected.name,
                                modifier = Modifier.height(240.dp),
                                previewBitmap = preview
                            )
                        }
                    }
                    Text(selected.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.selected_iwf_info), style = MaterialTheme.typography.bodySmall, color = MutedInk, textAlign = TextAlign.Center)
                    if (ble.connected.value) {
                        Button(
                            onClick = onInstall,
                            enabled = !readingFile && !ble.installing.value,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
                        ) {
                            Text(
                                if (ble.installing.value) stringResource(R.string.watch_uploading, ble.progress.intValue)
                                else stringResource(R.string.install_face),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = onOpenDevice,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
                        ) { Text(stringResource(R.string.connect_to_install), fontWeight = FontWeight.Bold) }
                    }
                    if (ble.installing.value) {
                        LinearProgressIndicator(
                            progress = ble.progress.intValue / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = BrandYellow,
                            trackColor = Color(0xFFECE4C7)
                        )
                        Text(ble.status.value, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MutedInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicePage(ble: VeryFitBleClient, onConnect: () -> Unit, onSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = BrandYellow)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WatchMark(Modifier.size(72.dp), color = Color.Black)
            Text(
                stringResource(if (ble.connected.value) R.string.device_connected_heading else R.string.device_heading),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center
            )
            Text(stringResource(R.string.device_description), style = MaterialTheme.typography.bodyMedium, color = Ink, textAlign = TextAlign.Center)
            if (!ble.connected.value && ble.status.value.isNotBlank()) {
                Text(ble.status.value, style = MaterialTheme.typography.bodySmall, color = Ink, textAlign = TextAlign.Center)
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = CardWhite)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ble.connected.value || ble.reconnecting.value) {
                OutlinedButton(
                    onClick = { ble.error.value = null; ble.disconnect() },
                    enabled = !ble.installing.value,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Text(stringResource(R.string.disconnect_watch), color = Ink, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = !ble.scanning.value && !ble.installing.value,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = Ink)
                ) { Text(if (ble.scanning.value) stringResource(R.string.scanning) else stringResource(R.string.connect_watch), fontWeight = FontWeight.Bold) }
            }
            if (ble.scanning.value) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BrandYellow, trackColor = Color(0xFFECE4C7))
            ble.devices.value.forEach { watch ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !ble.installing.value) { ble.error.value = null; ble.connect(watch) },
                    shape = RoundedCornerShape(17.dp), color = PageYellow, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE6D78D))
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(watch.name, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(watch.address, style = MaterialTheme.typography.labelSmall, color = MutedInk)
                    }
                }
            }
            if (ble.error.value != null) NoticeCard(ble.error.value.orEmpty(), isError = true)
            Text(stringResource(R.string.bluetooth_help), style = MaterialTheme.typography.bodySmall, color = MutedInk)
            TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.permission_settings), color = Ink)
            }
        }
    }
}

@Composable
private fun ConnectionPill(ble: VeryFitBleClient, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = CardWhite, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE6D78D))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                stringResource(if (ble.connected.value) R.string.connected_badge else R.string.disconnected_badge),
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (ble.connected.value) Color(0xFF238641) else Color(0xFFC62828)))
        }
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) Color(0xFFFFE7E2) else Color(0xFFE7F4E7)
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = if (isError) ErrorRed else Color(0xFF205E2B))
    }
}

@Composable
private fun StoreTabIcon(isDevice: Boolean, selected: Boolean) {
    val color = if (selected) Ink else MutedInk
    if (isDevice) {
        WatchMark(Modifier.size(24.dp), color = color)
        return
    }
    Canvas(Modifier.size(24.dp)) {
        val sw = 1.9.dp.toPx()
        val gap = size.width * .11f
        val block = (size.width - gap) / 2f
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Size(block, block), androidx.compose.ui.geometry.CornerRadius(sw * 1.5f), style = Stroke(sw))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(block + gap, 0f), androidx.compose.ui.geometry.Size(block, block), androidx.compose.ui.geometry.CornerRadius(sw * 1.5f), style = Stroke(sw))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(0f, block + gap), androidx.compose.ui.geometry.Size(block, block), androidx.compose.ui.geometry.CornerRadius(sw * 1.5f), style = Stroke(sw))
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(block + gap, block + gap), androidx.compose.ui.geometry.Size(block, block), androidx.compose.ui.geometry.CornerRadius(sw * 1.5f), style = Stroke(sw))
    }
}

@Composable
private fun InstallTabIcon(selected: Boolean) {
    val color = if (selected) Ink else MutedInk
    Canvas(Modifier.size(24.dp)) {
        val sw = 1.9.dp.toPx()
        val centerX = size.width / 2f
        drawLine(color, androidx.compose.ui.geometry.Offset(centerX, size.height * 0.08f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.53f), sw, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.31f, size.height * 0.38f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.56f), sw, cap = StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.69f, size.height * 0.38f), androidx.compose.ui.geometry.Offset(centerX, size.height * 0.56f), sw, cap = StrokeCap.Round)
        val tray = Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.56f)
            lineTo(size.width * 0.14f, size.height * 0.88f)
            lineTo(size.width * 0.86f, size.height * 0.88f)
            lineTo(size.width * 0.86f, size.height * 0.56f)
        }
        drawPath(tray, color, style = Stroke(width = sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun SettingsGearIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.43f
        val ringRadius = size.minDimension * 0.28f
        val stroke = 2.dp.toPx()
        for (tooth in 0 until 8) {
            val angle = tooth * (Math.PI / 4.0)
            val inner = outerRadius * 0.72f
            val start = androidx.compose.ui.geometry.Offset(
                center.x + (inner * kotlin.math.cos(angle)).toFloat(),
                center.y + (inner * kotlin.math.sin(angle)).toFloat()
            )
            val end = androidx.compose.ui.geometry.Offset(
                center.x + (outerRadius * kotlin.math.cos(angle)).toFloat(),
                center.y + (outerRadius * kotlin.math.sin(angle)).toFloat()
            )
            drawLine(color, start, end, stroke, cap = StrokeCap.Square)
        }
        drawCircle(color, ringRadius, center, style = Stroke(stroke))
        drawCircle(color, size.minDimension * 0.075f, center)
    }
}

@Composable
private fun WatchMark(modifier: Modifier = Modifier, color: Color) {
    Image(
        painter = painterResource(R.drawable.watch_connect_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(color)
    )
}
