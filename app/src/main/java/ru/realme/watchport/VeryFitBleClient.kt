package ru.realme.watchport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.util.ArrayDeque
import java.io.ByteArrayOutputStream
import java.util.UUID

internal data class WatchCandidate(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

/** Direct VeryFit BLE face transfer for Realme Watch 5. */
internal class VeryFitBleClient(private val context: Context) {
    val status = mutableStateOf(context.getString(R.string.watch_disconnected))
    val devices = mutableStateOf<List<WatchCandidate>>(emptyList())
    val scanning = mutableStateOf(false)
    val connected = mutableStateOf(false)
    val reconnecting = mutableStateOf(false)
    val connectedWatchName = mutableStateOf<String?>(null)
    val connectedWatchNumber = mutableStateOf<String?>(null)
    val installing = mutableStateOf(false)
    val progress = mutableIntStateOf(0)
    val error = mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var scanCallback: ScanCallback? = null
    private val found = linkedMapOf<String, WatchCandidate>()
    private var gatt: BluetoothGatt? = null
    private var connectionCandidate: WatchCandidate? = null
    private var desiredConnection = false
    private var connectionEverReady = false
    private var reconnectAttempts = 0
    private var uploadInterruptedByReconnect = false
    private var storedAuthAttempt = false
    private var rebindAttempted = false
    private var pendingAuth: ByteArray? = null
    private var authenticated = false
    private var watchfaceListReceived = false
    private var watchSlots = 0
    private var watchFaceCount = 0
    private var watchFreeBytes = 0L
    private var occupiedFiles = emptySet<String>()
    private var writer: BluetoothGattCharacteristic? = null
    private var notifier: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var writeWithResponse = true
    private var supportsWriteWithoutResponse = false
    private var notificationReady = false
    private var notificationSetupStarted = false
    private var writeRetryCount = 0
    private var nextWriteAllowedAtMs = 0L
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var manualDisconnect = false
    private var pendingUpload: Upload? = null
    private var activeUpload: Upload? = null
    private var uploadFileName = ""
    private var uploadSent = 0
    private var uploadAcknowledged = 0
    private var uploadAckBatchCount = 0
    private var closeQueued = false
    private var awaitingApplyAck = false
    private var protocolSequence = 1
    private var pendingProtocolFrame: ByteArrayOutputStream? = null
    private var pendingProtocolFrameLength = 0
    private var lastWriteOpcode = -1
    private var lastWriteSize = 0

    private enum class UploadMode { BUILT_IN, SELECTED_FILE }
    private data class Upload(
        val iwf: ByteArray,
        val packed: ByteArray,
        val title: String,
        val mode: UploadMode,
        val selectedFileName: String? = null
    )

    private val scanTimeout = Runnable { stopScan(showResults = true) }
    private val connectionTimeout = Runnable {
        if (gatt != null && !connected.value) failConnection(context.getString(R.string.error_connection_failed))
    }
    private val serviceDiscoveryTimeout = Runnable {
        if (gatt != null && connected.value && writer == null) failConnection(context.getString(R.string.error_services_failed))
    }
    private val mtuTimeout = Runnable { gatt?.let(::enableNotifications) }
    private val notificationTimeout = Runnable {
        if (!notificationReady) failConnection(context.getString(R.string.error_notifications_failed))
    }
    private val initializationTimeout = Runnable {
        if (notificationReady && !watchfaceListReceived) {
            val message = if (authenticated) context.getString(R.string.error_watch_slots)
            else context.getString(R.string.error_auth_challenge)
            Log.e(TAG, "Watch initialization timed out: authenticated=$authenticated")
            failConnection(message)
        }
    }
    private val uploadTimeout = Runnable {
        if (installing.value) finishUpload(false, context.getString(R.string.error_upload_timeout))
    }
    private val reconnectRunnable = Runnable { reconnectWatch() }
    private val rejectedWriteRetry = Runnable { drainWrites() }
    private val pacedWriteAdvance = Runnable { drainWrites() }
    private val noResponseWriteAdvance = Runnable {
        writeInFlight = false
        drainWrites()
    }

    fun startScan() {
        val bluetooth = adapter
        if (bluetooth == null) {
            error.value = context.getString(R.string.error_bluetooth_unsupported)
            return
        }
        if (!bluetooth.isEnabled) {
            error.value = context.getString(R.string.error_bluetooth_disabled)
            return
        }
        stopScan(showResults = false)
        try {
            val scanner = bluetooth.bluetoothLeScanner
            if (scanner == null) {
                error.value = context.getString(R.string.error_bluetooth_unsupported)
                return
            }
            found.clear()
            devices.value = emptyList()
            scanning.value = true
            status.value = context.getString(R.string.watch_scanning)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord
                    val advertisesService = record?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                    val name = try {
                        result.device.name ?: record?.deviceName
                    } catch (_: SecurityException) {
                        record?.deviceName
                    }
                    if (!advertisesService && name.isNullOrBlank()) return
                    val address = result.device.address ?: return
                    val candidate = WatchCandidate(
                        result.device,
                        name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown_watch),
                        address,
                        result.rssi
                    )
                    found[address] = candidate
                    devices.value = found.values.sortedByDescending { it.rssi }
                }

                override fun onScanFailed(errorCode: Int) {
                    scanning.value = false
                    status.value = context.getString(R.string.watch_scan_failed)
                    error.value = context.getString(R.string.error_scan_failed, errorCode)
                }
            }
            scanCallback = callback
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            main.postDelayed(scanTimeout, SCAN_DURATION_MS)
        } catch (e: SecurityException) {
            scanning.value = false
            error.value = context.getString(R.string.error_bluetooth_permission)
        } catch (e: Exception) {
            scanning.value = false
            error.value = e.localizedMessage ?: context.getString(R.string.error_scan_failed, -1)
        }
    }

    private fun stopScan(showResults: Boolean) {
        main.removeCallbacks(scanTimeout)
        val callback = scanCallback
        scanCallback = null
        try {
            if (callback != null) adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // The permission can be revoked while the screen is open.
        } catch (_: Exception) {
        }
        scanning.value = false
        if (showResults && !connected.value) {
            status.value = if (devices.value.isEmpty()) context.getString(R.string.watch_not_found)
            else context.getString(R.string.watch_choose_device)
        }
    }

    fun connect(candidate: WatchCandidate) {
        stopScan(showResults = false)
        closeGatt()
        manualDisconnect = false
        desiredConnection = true
        reconnecting.value = false
        connectionCandidate = candidate
        connectionEverReady = false
        reconnectAttempts = 0
        uploadInterruptedByReconnect = false
        authenticated = false
        watchfaceListReceived = false
        storedAuthAttempt = false
        rebindAttempted = false
        pendingAuth = null
        watchSlots = 0
        watchFaceCount = 0
        watchFreeBytes = 0L
        occupiedFiles = emptySet()
        main.removeCallbacks(reconnectRunnable)
        connectedWatchName.value = candidate.name
        connectedWatchNumber.value = candidate.address
        mtu = 23
        writeQueue.clear()
        try {
            status.value = context.getString(R.string.watch_connecting, candidate.name)
            gatt = candidate.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: run {
                    error.value = context.getString(R.string.error_connection_failed)
                    return
                }
            main.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            error.value = context.getString(R.string.error_bluetooth_permission)
        } catch (e: Exception) {
            error.value = e.localizedMessage ?: context.getString(R.string.error_connection_failed)
        }
    }

    fun disconnect() {
        manualDisconnect = true
        desiredConnection = false
        reconnecting.value = false
        connectionCandidate = null
        connectionEverReady = false
        reconnectAttempts = 0
        uploadInterruptedByReconnect = false
        main.removeCallbacks(reconnectRunnable)
        stopScan(showResults = false)
        if (installing.value) finishUpload(false, context.getString(R.string.error_connection_lost))
        pendingUpload = null
        closeGatt()
        connected.value = false
        connectedWatchName.value = null
        connectedWatchNumber.value = null
        installing.value = false
        status.value = context.getString(R.string.watch_disconnected)
    }

    fun installBuiltIn(iwf: ByteArray, packed: ByteArray, title: String) {
        queueUpload(Upload(iwf, packed, title, UploadMode.BUILT_IN))
    }

    fun installSelectedFile(iwf: ByteArray, packed: ByteArray, title: String, fileName: String) {
        queueUpload(Upload(iwf, packed, title, UploadMode.SELECTED_FILE, fileName))
    }

    private fun queueUpload(upload: Upload) {
        if (!connected.value || !notificationReady) {
            error.value = context.getString(R.string.error_watch_not_ready)
            return
        }
        if (installing.value || pendingUpload != null) {
            error.value = context.getString(R.string.error_install_in_progress)
            return
        }
        pendingUpload = upload
        if (watchfaceListReceived) startPendingUpload()
        else status.value = context.getString(R.string.watch_reading_slots)
    }

    fun close() {
        disconnect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, statusCode: Int, newState: Int) {
            main.post {
                if (callbackGatt !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(connectionTimeout)
                    connected.value = true
                    status.value = context.getString(R.string.watch_discovering)
                    try {
                        if (!callbackGatt.discoverServices()) failConnection(context.getString(R.string.error_services_failed))
                        else main.postDelayed(serviceDiscoveryTimeout, SERVICE_DISCOVERY_TIMEOUT_MS)
                    } catch (_: SecurityException) {
                        failConnection(context.getString(R.string.error_bluetooth_permission))
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasManual = manualDisconnect
                    val reconnectWanted = desiredConnection && !wasManual && connectionEverReady
                    val disconnectMessage = if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        context.getString(R.string.error_connection_lost)
                    } else {
                        context.getString(R.string.error_gatt_status, statusCode)
                    }
                    val uploadWasInterrupted = installing.value
                    Log.w(TAG, "GATT disconnected: status=$statusCode manual=$wasManual uploadActive=$uploadWasInterrupted reconnect=$reconnectWanted")
                    connected.value = false
                    connectedWatchName.value = null
                    connectedWatchNumber.value = null
                    authenticated = false
                    watchfaceListReceived = false
                    pendingProtocolFrame = null
                    pendingProtocolFrameLength = 0
                    main.removeCallbacks(initializationTimeout)
                    if (uploadWasInterrupted) finishUpload(false, disconnectMessage)
                if (uploadWasInterrupted && reconnectWanted) uploadInterruptedByReconnect = true
                    installing.value = false
                    notificationReady = false
                    writeQueue.clear()
                    writeInFlight = false
                    closeGatt()
                    if (!wasManual) {
                        error.value = disconnectMessage
                        if (reconnectWanted) scheduleReconnect(disconnectMessage)
                        else status.value = context.getString(R.string.watch_disconnected)
                    }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, statusCode: Int) {
            main.post {
                if (callbackGatt !== gatt) return@post
                main.removeCallbacks(serviceDiscoveryTimeout)
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(context.getString(R.string.error_services_failed))
                    return@post
                }
                val service = callbackGatt.getService(SERVICE_UUID)
                writer = service?.getCharacteristic(WRITE_UUID)
                notifier = service?.getCharacteristic(NOTIFY_UUID)
                if (writer == null || notifier == null) {
                    failConnection(context.getString(R.string.error_not_gtx10))
                    return@post
                }
                val props = writer!!.properties
                val supportsWriteWithResponse =
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                supportsWriteWithoutResponse =
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                if (!supportsWriteWithResponse && !supportsWriteWithoutResponse) {
                    failConnection(context.getString(R.string.error_not_gtx10))
                    return@post
                }
                writeWithResponse = supportsWriteWithResponse
                Log.i(TAG, "Watch write characteristic properties=$props; responseWrites=$writeWithResponse noResponseWrites=$supportsWriteWithoutResponse")
                try {
                    if (!callbackGatt.requestMtu(DESIRED_MTU)) enableNotifications(callbackGatt)
                    else main.postDelayed(mtuTimeout, MTU_TIMEOUT_MS)
                } catch (_: SecurityException) {
                    failConnection(context.getString(R.string.error_bluetooth_permission))
                }
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, value: Int, statusCode: Int) {
            main.post {
                if (callbackGatt !== gatt) return@post
                main.removeCallbacks(mtuTimeout)
                mtu = if (statusCode == BluetoothGatt.GATT_SUCCESS) value else 23
                Log.i(TAG, "Negotiated BLE MTU=$mtu status=$statusCode")
                enableNotifications(callbackGatt)
            }
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            main.post {
                if (callbackGatt !== gatt) return@post
                main.removeCallbacks(notificationTimeout)
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(context.getString(R.string.error_notifications_failed))
                    return@post
                }
                notificationReady = true
                notificationSetupStarted = false
                connectionEverReady = true
                reconnectAttempts = 0
                if (reconnecting.value) {
                    error.value = if (uploadInterruptedByReconnect) {
                        context.getString(R.string.error_upload_interrupted_reconnected)
                    } else {
                        null
                    }
                    uploadInterruptedByReconnect = false
                }
                reconnecting.value = false
                status.value = context.getString(R.string.watch_authenticating)
                Log.i(TAG, "GATT notifications enabled; starting VeryFit bind handshake")
                beginBind()
            }
        }

        @Deprecated("Deprecated by Android 13 callback overload")
        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value?.copyOf() ?: return
            onIncoming(callbackGatt, characteristic.uuid, data)
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onIncoming(callbackGatt, characteristic.uuid, value.copyOf())
        }

        override fun onCharacteristicWrite(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            main.post {
                if (callbackGatt !== gatt) return@post
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    writeInFlight = false
                    Log.e(TAG, "GATT write callback failed: opcode=$lastWriteOpcode bytes=$lastWriteSize status=$statusCode")
                    failConnection(context.getString(R.string.error_write_callback_failed, lastWriteOpcode, lastWriteSize, statusCode))
                    return@post
                }
                writeInFlight = false
                writeRetryCount = 0
                main.removeCallbacks(pacedWriteAdvance)
                main.removeCallbacks(noResponseWriteAdvance)
                if (installing.value && closeQueued && lastWriteOpcode == 3 && writeQueue.isEmpty()) {
                    progress.intValue = 99
                    status.value = context.getString(R.string.watch_finalizing)
                }
                drainWrites()
            }
        }
    }

    private fun enableNotifications(callbackGatt: BluetoothGatt) {
        if (notificationReady || notificationSetupStarted) return
        try {
            val characteristic = notifier ?: run {
                failConnection(context.getString(R.string.error_not_gtx10))
                return
            }
            if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
                failConnection(context.getString(R.string.error_notifications_failed))
                return
            }
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                failConnection(context.getString(R.string.error_notifications_failed))
                return
            }
            notificationSetupStarted = true
            if (Build.VERSION.SDK_INT >= 33) {
                val result = callbackGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (result != BluetoothGatt.GATT_SUCCESS) failConnection(context.getString(R.string.error_notifications_failed))
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (!callbackGatt.writeDescriptor(descriptor)) failConnection(context.getString(R.string.error_notifications_failed))
            }
            if (notificationSetupStarted) main.postDelayed(notificationTimeout, NOTIFICATION_TIMEOUT_MS)
            status.value = context.getString(R.string.watch_authenticating)
        } catch (_: SecurityException) {
            failConnection(context.getString(R.string.error_bluetooth_permission))
        }
    }

    private fun beginBind() {
        if (!notificationReady) return
        main.removeCallbacks(initializationTimeout)
        main.postDelayed(initializationTimeout, INITIALIZATION_TIMEOUT_MS)
        val savedAuth = try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(authPreferenceKey(), null)
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
        } catch (_: Exception) {
            null
        }
        if (savedAuth != null && savedAuth.isNotEmpty()) {
            storedAuthAttempt = true
            Log.i(TAG, "Authenticating with saved VeryFit bind key")
            sendShort(4, 5, byteArrayOf(savedAuth.size.toByte(), (savedAuth.size ushr 8).toByte()) + savedAuth)
        } else {
            sendBindRequest()
        }
    }

    private fun sendBindRequest() {
        storedAuthAttempt = false
        Log.i(TAG, "Requesting VeryFit bind challenge")
        sendShort(4, 1, byteArrayOf(2, 2, 1, 3))
    }

    private fun handleShortPacket(group: Int, key: Int, payload: ByteArray) {
        if (group != 4) return
        when (key) {
            1 -> {
                if (payload.size < 15 || payload[0].toInt() != 0) {
                    failConnection(context.getString(R.string.error_auth_challenge))
                    return
                }
                val challengeLength = uint16(payload, 1)
                if (challengeLength < 12 || payload.size < 3 + challengeLength) {
                    failConnection(context.getString(R.string.error_auth_challenge))
                    return
                }
                val challenge = payload.copyOfRange(3, 15)
                val response = ByteArray(12)
                for (index in 0 until 6) {
                    response[index] = challenge[index + 6]
                    response[index + 6] = (challenge[index].toInt() xor challenge[index + 6].toInt()).toByte()
                }
                pendingAuth = response
                Log.i(TAG, "Received VeryFit challenge; sending bind response")
                sendShort(4, 5, byteArrayOf(response.size.toByte(), 0) + response)
            }
            3, 5 -> {
                val accepted = payload.firstOrNull()?.toInt() == 0
                if (accepted) {
                    pendingAuth?.let { auth ->
                        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                            .putString(authPreferenceKey(), Base64.encodeToString(auth, Base64.NO_WRAP))
                            .apply()
                    }
                    pendingAuth = null
                    authenticated = true
                    watchfaceListReceived = false
                    status.value = context.getString(R.string.watch_reading_slots)
                    Log.i(TAG, "VeryFit bind accepted; querying watchface slots and free storage")
                    sendFramed(CMD_WATCHFACE_LIST, ByteArray(0))
                } else if (storedAuthAttempt && !rebindAttempted) {
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .remove(authPreferenceKey())
                        .apply()
                    rebindAttempted = true
                    Log.w(TAG, "Saved VeryFit bind key rejected; requesting a fresh challenge")
                    sendBindRequest()
                } else {
                    failConnection(context.getString(R.string.error_auth_rejected))
                }
            }
        }
    }

    private fun authPreferenceKey(): String {
        val address = try { gatt?.device?.address ?: "unknown" } catch (_: SecurityException) { "unknown" }
        return "veryfit_auth_$address"
    }

    private fun onIncoming(callbackGatt: BluetoothGatt, characteristicUuid: UUID, data: ByteArray) {
        main.post {
            if (callbackGatt !== gatt || characteristicUuid != NOTIFY_UUID || data.isEmpty()) return@post
            when (data[0].toInt() and 0xff) {
                FILE_MARKER -> handleFileReply(data)
                FRAME_MARKER -> handleProtocolFragment(data)
                else -> handleProtocolFragment(data)
            }
        }
    }

    private fun startPendingUpload() {
        if (!authenticated || !watchfaceListReceived) return
        val upload = pendingUpload ?: return
        if (watchSlots <= 0 || watchFaceCount >= watchSlots) {
            pendingUpload = null
            error.value = context.getString(R.string.error_watch_slots_full)
            Log.w(TAG, "Upload stopped: watch has $watchFaceCount faces in $watchSlots slots")
            return
        }
        if (upload.iwf.size.toLong() > watchFreeBytes) {
            pendingUpload = null
            error.value = context.getString(R.string.error_watch_space, upload.iwf.size, watchFreeBytes)
            Log.w(TAG, "Upload stopped: face needs ${upload.iwf.size} bytes, watch reports $watchFreeBytes free")
            return
        }
        val fileNumber = (1..(watchSlots + watchFaceCount + 10))
            .firstOrNull { "static_$it.iwf" !in occupiedFiles }
        if (fileNumber == null) {
            pendingUpload = null
            error.value = context.getString(R.string.error_watch_slots_full)
            Log.w(TAG, "Upload stopped: no unused static_N.iwf filename")
            return
        }
        pendingUpload = null
        activeUpload = upload
        uploadFileName = "static_$fileNumber.iwf.lz"
        uploadSent = 0
        uploadAcknowledged = 0
        uploadAckBatchCount = 0
        closeQueued = false
        awaitingApplyAck = false
        nextWriteAllowedAtMs = 0L
        pendingProtocolFrame = null
        pendingProtocolFrameLength = 0
        protocolSequence = 1
        installing.value = true
        progress.intValue = 0
        status.value = context.getString(R.string.watch_upload_starting)
        try {
            val requested = gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) == true
            Log.i(TAG, "Requested high BLE connection priority for upload: accepted=$requested")
        } catch (_: SecurityException) {
            Log.w(TAG, "Could not request high BLE connection priority for upload")
        }
        Log.i(TAG, "Start watchface upload: route=${upload.mode} filename=$uploadFileName iwfBytes=${upload.iwf.size} packedBytes=${upload.packed.size} freeBytes=$watchFreeBytes slots=$watchFaceCount/$watchSlots")
        sendFileOpen(operation = 8, size = upload.iwf.size, filename = uploadFileName)
    }

    private fun handleFileReply(packet: ByteArray) {
        if (!installing.value || packet.size < 3) return
        main.removeCallbacks(uploadTimeout)
        if (packet[2].toInt() != 0) {
            finishUpload(false, context.getString(R.string.error_watch_refused, packet[2].toInt() and 0xff))
            return
        }
        when (packet[1].toInt() and 0xff) {
            8 -> {
                val data = activeUpload ?: return
                sendFileOpen(operation = 1, size = data.packed.size, filename = uploadFileName)
            }
            1 -> {
                val batchSize = if (activeUpload?.mode == UploadMode.SELECTED_FILE) {
                    SELECTED_FILE_ACK_CHUNKS
                } else {
                    BUILT_IN_ACK_CHUNKS
                }
                enqueue(byteArrayOf(FILE_MARKER.toByte(), 5, batchSize.toByte()))
                armUploadTimeout()
            }
            2 -> {
                uploadAcknowledged = uploadSent
                uploadAckBatchCount++
                if (uploadAckBatchCount % 10 == 0) {
                    Log.i(TAG, "Upload progress acknowledged: $uploadAcknowledged/${activeUpload?.packed?.size ?: 0} bytes")
                }
                val data = activeUpload?.packed ?: return
                if (uploadSent < data.size) sendUploadBatch()
                else if (activeUpload?.mode == UploadMode.SELECTED_FILE && !closeQueued) {
                    closeQueued = true
                    status.value = context.getString(R.string.watch_finalizing)
                    enqueue(selectedFileEndPacket(data))
                    armUploadTimeout(FINALIZE_TIMEOUT_MS)
                }
            }
            5 -> sendUploadBatch()
            3 -> {
                if (activeUpload?.mode == UploadMode.SELECTED_FILE) {
                    sendApplyWatchFace()
                } else {
                    finishUpload(true, context.getString(R.string.watch_upload_done, activeUpload?.title ?: uploadFileName.removeSuffix(".lz")))
                }
            }
        }
    }

    private fun sendUploadBatch() {
        val upload = activeUpload ?: return
        val data = upload.packed
        // Use the full payload allowed by the negotiated MTU while retaining
        // acknowledged GATT writes for delivery reliability.
        val maxChunk = minOf(mtu - 6, MAX_FILE_PAYLOAD).coerceAtLeast(1)
        val batchLimit = if (upload.mode == UploadMode.SELECTED_FILE) {
            SELECTED_FILE_ACK_CHUNKS
        } else {
            BUILT_IN_ACK_CHUNKS
        }
        var count = 0
        while (uploadSent < data.size && count < batchLimit) {
            val size = minOf(maxChunk, data.size - uploadSent)
            val packet = ByteArray(size + 3)
            packet[0] = FILE_MARKER.toByte()
            packet[1] = 2
            data.copyInto(packet, destinationOffset = 3, startIndex = uploadSent, endIndex = uploadSent + size)
            enqueue(packet)
            uploadSent += size
            count++
        }
        if (uploadSent >= data.size && !closeQueued && activeUpload?.mode == UploadMode.BUILT_IN) {
            // Gadgetbridge puts END_TRANSFER directly after the final data
            // chunks in the same acknowledged batch. Waiting for another D1/02
            // here leaves some watches stuck at 99% indefinitely.
            closeQueued = true
            enqueue(closePacket(data))
            Log.i(TAG, "Queue final watchface packet after ${data.size} bytes")
        }
        progress.intValue = ((uploadAcknowledged.toLong() * 100L) / data.size.coerceAtLeast(1)).toInt().coerceAtMost(99)
        status.value = context.getString(R.string.watch_uploading, progress.intValue)
        armUploadTimeout(if (closeQueued) FINALIZE_TIMEOUT_MS else UPLOAD_TIMEOUT_MS)
    }

    private fun finishUpload(success: Boolean, message: String) {
        installing.value = false
        awaitingApplyAck = false
        pendingProtocolFrame = null
        pendingProtocolFrameLength = 0
        progress.intValue = if (success) 100 else progress.intValue
        activeUpload = null
        pendingUpload = null
        status.value = message
        if (!success) error.value = message
        main.removeCallbacks(uploadTimeout)
        try {
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        } catch (_: SecurityException) {
            Log.w(TAG, "Could not restore balanced BLE connection priority")
        }
        if (success) {
            watchfaceListReceived = false
            main.postDelayed({ requestWatchfaceList() }, WATCHFACE_LIST_REFRESH_DELAY_MS)
        }
    }

    private fun sendFileOpen(operation: Int, size: Int, filename: String) {
        val name = filename.toByteArray(Charsets.UTF_8)
        val packet = ByteArray(8 + name.size)
        packet[0] = FILE_MARKER.toByte()
        packet[1] = operation.toByte()
        packet[2] = 0xff.toByte()
        putInt32(packet, 3, size)
        packet[7] = 2
        name.copyInto(packet, destinationOffset = 8)
        Log.i(TAG, "Queue file transfer command: opcode=$operation size=$size filename=$filename")
        enqueue(packet)
        armUploadTimeout()
    }

    private fun armUploadTimeout(timeoutMs: Long = UPLOAD_TIMEOUT_MS) {
        main.removeCallbacks(uploadTimeout)
        if (installing.value) main.postDelayed(uploadTimeout, timeoutMs)
    }

    private fun closePacket(data: ByteArray): ByteArray {
        // Match Gadgetbridge's D1 END_TRANSFER packet: unsigned byte-sum and
        // lowercase hexadecimal MD5 in a fixed 128-byte field.
        val checksum = data.fold(0L) { sum, byte -> sum + (byte.toInt() and 0xff) } and 0xffff_ffffL
        val digest = java.security.MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .toByteArray(Charsets.UTF_8)
        val packet = ByteArray(134)
        packet[0] = FILE_MARKER.toByte()
        packet[1] = 3
        putInt32(packet, 2, checksum.toInt())
        digest.copyInto(packet, destinationOffset = 6, endIndex = minOf(digest.size, 128))
        return packet
    }

    private fun selectedFileEndPacket(data: ByteArray): ByteArray {
        // The selected-file route uses the separate END_TRANSFER exchange from
        // the sideloading protocol, then applies the uploaded face with V3.
        val checksum = java.util.zip.CRC32().apply { update(data) }.value.toInt()
        return ByteArray(6).apply {
            this[0] = FILE_MARKER.toByte()
            this[1] = 3
            putInt32(this, 2, checksum)
        }
    }

    private fun sendApplyWatchFace() {
        val name = uploadFileName.removeSuffix(".lz").toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + APPLY_NAME_FIELD_SIZE)
        payload[0] = 1
        name.copyInto(payload, destinationOffset = 1, endIndex = minOf(name.size, APPLY_NAME_FIELD_SIZE))
        awaitingApplyAck = true
        status.value = context.getString(R.string.watch_applying)
        sendFramed(CMD_APPLY_WATCHFACE, payload)
        armUploadTimeout(FINALIZE_TIMEOUT_MS)
    }

    private fun sendShort(group: Int, key: Int, payload: ByteArray) {
        val packet = ByteArray(payload.size + 2)
        packet[0] = group.toByte()
        packet[1] = key.toByte()
        payload.copyInto(packet, destinationOffset = 2)
        enqueue(packet)
    }

    private fun sendFramed(command: Int, payload: ByteArray) {
        val bodyLength = payload.size + 11
        val covered = ByteArrayOutputStream(bodyLength)
        covered.write(FRAME_MAGIC)
        covered.write(1)
        covered.write(bodyLength and 0xff)
        covered.write((bodyLength ushr 8) and 0xff)
        covered.write(command and 0xff)
        covered.write((command ushr 8) and 0xff)
        covered.write(protocolSequence and 0xff)
        covered.write((protocolSequence ushr 8) and 0xff)
        protocolSequence = (protocolSequence + 1) and 0xffff
        covered.write(payload)
        val coveredBytes = covered.toByteArray()
        val crc = crc16(coveredBytes, 0, coveredBytes.size)
        val frame = ByteArray(coveredBytes.size + 3)
        frame[0] = FRAME_MARKER.toByte()
        coveredBytes.copyInto(frame, destinationOffset = 1)
        frame[frame.size - 2] = crc.toByte()
        frame[frame.size - 1] = (crc ushr 8).toByte()
        enqueue(frame)
    }

    private fun handleProtocolFragment(data: ByteArray) {
        val unfinished = pendingProtocolFrame
        if (unfinished != null && (data[0].toInt() and 0xff) != FRAME_MARKER) {
            if (data.size > 1) unfinished.write(data, 1, data.size - 1)
            if (unfinished.size() >= pendingProtocolFrameLength) {
                val completed = unfinished.toByteArray().copyOf(pendingProtocolFrameLength)
                pendingProtocolFrame = null
                consumeProtocolFrame(completed)
            }
            return
        }
        if (unfinished != null) pendingProtocolFrame = null
        if ((data[0].toInt() and 0xff) != FRAME_MARKER) {
            if (data.size >= 2) {
                handleShortPacket(data[0].toInt() and 0xff, data[1].toInt() and 0xff, data.copyOfRange(2, data.size))
            }
            return
        }
        if (!hasFrameMagic(data) || data.size < 8) return
        val expected = uint16(data, 6) + 3
        if (data.size >= expected) consumeProtocolFrame(data.copyOf(expected))
        else {
            pendingProtocolFrameLength = expected
            pendingProtocolFrame = ByteArrayOutputStream(expected).apply { write(data) }
        }
    }

    private fun consumeProtocolFrame(frame: ByteArray) {
        if (!hasFrameMagic(frame) || frame.size < 14) return
        val declared = uint16(frame, 6)
        if (frame.size != declared + 3) return
        val storedCrc = uint16(frame, frame.size - 2)
        if (storedCrc != crc16(frame, 1, frame.size - 3)) return
        val command = uint16(frame, 8)
        val payload = frame.copyOfRange(12, frame.size - 2)
        when (command) {
            CMD_WATCHFACE_LIST -> if (authenticated) handleWatchfaceList(payload)
            CMD_APPLY_WATCHFACE -> if (awaitingApplyAck) {
                val success = payload.firstOrNull()?.toInt()?.and(0xff) == 0
                finishUpload(
                    success,
                    if (success) context.getString(R.string.watch_upload_done, activeUpload?.title ?: uploadFileName.removeSuffix(".lz"))
                    else context.getString(R.string.error_install)
                )
            }
        }
    }

    private fun handleWatchfaceList(payload: ByteArray) {
        main.removeCallbacks(initializationTimeout)
        if (payload.size < 63) {
            watchfaceListReceived = false
            error.value = context.getString(R.string.error_watch_slots)
            Log.e(TAG, "Watchface list is too short: ${payload.size} bytes")
            return
        }
        watchSlots = uint16(payload, 1)
        watchFreeBytes = uint32(payload, 46)
        watchFaceCount = payload[62].toInt() and 0xff
        val filenames = linkedSetOf<String>()
        for (index in 0 until watchFaceCount) {
            val offset = 63 + index * WATCHFACE_ENTRY_SIZE
            if (offset + WATCHFACE_ENTRY_SIZE > payload.size) break
            val name = readCString(payload, offset + 10, 30)
            if (name.isNotBlank()) filenames += name
        }
        occupiedFiles = filenames
        watchfaceListReceived = true
        status.value = context.getString(R.string.watch_ready)
        error.value = null
        Log.i(TAG, "Watchface storage ready: faces=$watchFaceCount/$watchSlots freeBytes=$watchFreeBytes files=${filenames.joinToString()}")
        startPendingUpload()
    }

    private fun requestWatchfaceList() {
        if (!notificationReady || !authenticated) return
        watchfaceListReceived = false
        status.value = context.getString(R.string.watch_reading_slots)
        main.removeCallbacks(initializationTimeout)
        main.postDelayed(initializationTimeout, INITIALIZATION_TIMEOUT_MS)
        sendFramed(CMD_WATCHFACE_LIST, ByteArray(0))
    }

    private fun hasFrameMagic(data: ByteArray): Boolean = data.size >= 5 &&
        (data[0].toInt() and 0xff) == FRAME_MARKER &&
        data[1] == FRAME_MAGIC[0] && data[2] == FRAME_MAGIC[1] &&
        data[3] == FRAME_MAGIC[2] && data[4] == FRAME_MAGIC[3]

    private fun uint16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xffL) or
            ((data[offset + 1].toLong() and 0xffL) shl 8) or
            ((data[offset + 2].toLong() and 0xffL) shl 16) or
            ((data[offset + 3].toLong() and 0xffL) shl 24)

    private fun readCString(data: ByteArray, offset: Int, length: Int): String {
        val end = (offset until minOf(data.size, offset + length)).firstOrNull { data[it] == 0.toByte() }
            ?: minOf(data.size, offset + length)
        return data.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xffff
        for (i in offset until offset + length) {
            crc = (((crc ushr 8) or (crc shl 8)) and 0xffff) xor (data[i].toInt() and 0xff)
            crc = crc xor ((crc and 0xff) ushr 4)
            crc = crc xor ((crc shl 12) and 0xffff)
            crc = crc xor (((crc and 0xff) shl 5) and 0xffff)
            crc = crc and 0xffff
        }
        return crc
    }

    private fun enqueue(packet: ByteArray) {
        if (gatt == null || writer == null) {
            failConnection(context.getString(R.string.error_connection_lost))
            return
        }
        writeQueue.addLast(packet)
        drainWrites()
    }

    private fun drainWrites() {
        if (writeInFlight || writeQueue.isEmpty()) return
        val activeGatt = gatt ?: return
        val characteristic = writer ?: return
        val nextPacket = writeQueue.peekFirst() ?: return
        val nextOpcode = nextPacket.getOrNull(1)?.toInt()?.and(0xff)
        // The watch's built-in catalogue transfer historically used ATT Write
        // Commands for file chunks, with protocol ACKs after a bounded window.
        // Keep control packets and user-selected files on acknowledged writes.
        val useWriteWithResponse = !(activeUpload?.mode == UploadMode.BUILT_IN &&
            nextOpcode == 2 && supportsWriteWithoutResponse) && writeWithResponse
        if (!useWriteWithResponse) {
            val waitMs = nextWriteAllowedAtMs - SystemClock.elapsedRealtime()
            if (waitMs > 0) {
                main.removeCallbacks(pacedWriteAdvance)
                main.postDelayed(pacedWriteAdvance, waitMs)
                return
            }
        }
        val packet = writeQueue.removeFirst()
        writeInFlight = true
        lastWriteOpcode = packet.getOrNull(1)?.toInt()?.and(0xff) ?: -1
        lastWriteSize = packet.size
        if (lastWriteOpcode != 2) {
            Log.i(TAG, "Queue BLE write: opcode=$lastWriteOpcode bytes=$lastWriteSize mtu=$mtu response=$useWriteWithResponse")
        }
        try {
            val type = if (useWriteWithResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= 33) {
                val result = activeGatt.writeCharacteristic(characteristic, packet, type)
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    writeInFlight = false
                    Log.e(TAG, "Bluetooth API rejected write: opcode=$lastWriteOpcode bytes=$lastWriteSize result=$result")
                    val busy = Build.VERSION.SDK_INT >= 33 && result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                    if (!busy || !retryRejectedWrite(packet, result)) {
                        failConnection(context.getString(R.string.error_write_api_failed, lastWriteOpcode, lastWriteSize, result))
                    }
                } else {
                    writeRetryCount = 0
                    if (!useWriteWithResponse) {
                        nextWriteAllowedAtMs = SystemClock.elapsedRealtime() + NO_RESPONSE_WRITE_INTERVAL_MS
                        main.removeCallbacks(noResponseWriteAdvance)
                        main.postDelayed(noResponseWriteAdvance, NO_RESPONSE_WRITE_INTERVAL_MS)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = type
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                val started = activeGatt.writeCharacteristic(characteristic)
                if (!started) {
                    writeInFlight = false
                    Log.e(TAG, "Android rejected write locally: opcode=$lastWriteOpcode bytes=$lastWriteSize mtu=$mtu props=${characteristic.properties} response=$useWriteWithResponse")
                    if (!retryRejectedWrite(packet, null)) {
                        failConnection(context.getString(R.string.error_write_local_failed, lastWriteOpcode, lastWriteSize))
                    }
                } else {
                    writeRetryCount = 0
                    if (!useWriteWithResponse) {
                        nextWriteAllowedAtMs = SystemClock.elapsedRealtime() + NO_RESPONSE_WRITE_INTERVAL_MS
                        main.removeCallbacks(noResponseWriteAdvance)
                        main.postDelayed(noResponseWriteAdvance, NO_RESPONSE_WRITE_INTERVAL_MS)
                    }
                }
            }
        } catch (_: SecurityException) {
            writeInFlight = false
            failConnection(context.getString(R.string.error_bluetooth_permission))
        }
    }

    private fun retryRejectedWrite(packet: ByteArray, status: Int?): Boolean {
        if (!connected.value || !notificationReady || writeRetryCount >= MAX_WRITE_RETRIES || gatt == null) return false
        writeRetryCount++
        writeQueue.addFirst(packet)
        val delay = WRITE_RETRY_DELAY_MS * writeRetryCount
        Log.w(TAG, "Retry BLE write ${writeRetryCount}/$MAX_WRITE_RETRIES after ${delay}ms (status=${status ?: "legacy-busy"})")
        main.removeCallbacks(rejectedWriteRetry)
        main.postDelayed(rejectedWriteRetry, delay)
        return true
    }

    private fun failConnection(message: String) {
        Log.e(TAG, "BLE connection failure: $message")
        val reconnectWanted = desiredConnection && connectionEverReady && !manualDisconnect
        error.value = message
        if (installing.value) finishUpload(false, message)
        status.value = context.getString(R.string.watch_connection_failed)
        connected.value = false
                    reconnecting.value = reconnectWanted
        connectedWatchName.value = null
        connectedWatchNumber.value = null
        notificationReady = false
        closeGatt()
        if (reconnectWanted) scheduleReconnect(message)
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredConnection || manualDisconnect || !connectionEverReady || connectionCandidate == null) return
        val multiplier = (1 shl reconnectAttempts.coerceAtMost(RECONNECT_MAX_SHIFT)).toLong()
        val delay = (RECONNECT_BASE_DELAY_MS * multiplier).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempts++
        reconnecting.value = true
        status.value = context.getString(R.string.watch_reconnecting, connectionCandidate?.name.orEmpty())
        Log.w(TAG, "Scheduling watch reconnect in ${delay}ms (attempt=$reconnectAttempts): $reason")
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    private fun reconnectWatch() {
        if (!desiredConnection || manualDisconnect || !connectionEverReady || gatt != null) return
        val candidate = connectionCandidate ?: return
        try {
            reconnecting.value = true
            status.value = context.getString(R.string.watch_connecting, candidate.name)
            connectedWatchName.value = candidate.name
            connectedWatchNumber.value = candidate.address
            mtu = 23
            notificationReady = false
            notificationSetupStarted = false
            authenticated = false
            watchfaceListReceived = false
            pendingAuth = null
            pendingProtocolFrame = null
            pendingProtocolFrameLength = 0
            writeQueue.clear()
            writeInFlight = false
            gatt = candidate.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                scheduleReconnect(context.getString(R.string.error_connection_failed))
                return
            }
            Log.i(TAG, "Reconnecting to watch ${candidate.name}")
            main.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            scheduleReconnect(context.getString(R.string.error_bluetooth_permission))
        } catch (e: Exception) {
            scheduleReconnect(e.localizedMessage ?: context.getString(R.string.error_connection_failed))
        }
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        writer = null
        notifier = null
        notificationReady = false
        writeQueue.clear()
        writeInFlight = false
        nextWriteAllowedAtMs = 0L
        writeRetryCount = 0
        main.removeCallbacks(rejectedWriteRetry)
        main.removeCallbacks(pacedWriteAdvance)
        main.removeCallbacks(noResponseWriteAdvance)
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(serviceDiscoveryTimeout)
        main.removeCallbacks(mtuTimeout)
        main.removeCallbacks(notificationTimeout)
        main.removeCallbacks(initializationTimeout)
        main.removeCallbacks(uploadTimeout)
        notificationSetupStarted = false
    }

    private fun putInt32(data: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) data[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun closeAndDisconnect() {
        closeGatt()
        connected.value = false
    }

    companion object {
        private const val TAG = "RealmeWatchPortBLE"
        private val SERVICE_UUID: UUID = UUID.fromString("00000af0-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("00000af6-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("00000af7-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFERENCES = "watch_port_ble"
        private const val FILE_MARKER = 0xd1
        private val FRAME_MAGIC = byteArrayOf(0xda.toByte(), 0xad.toByte(), 0xda.toByte(), 0xad.toByte())
        private const val FRAME_MARKER = 0x33
        private const val CMD_APPLY_WATCHFACE = 0x08
        private const val CMD_WATCHFACE_LIST = 0x31
        private const val APPLY_NAME_FIELD_SIZE = 30
        private const val WATCHFACE_ENTRY_SIZE = 40
        // Match the previously stable built-in route: short no-response writes
        // paced at 10 ms, followed by the watch's own ACK every 10 chunks.
        private const val BUILT_IN_ACK_CHUNKS = 10
        private const val SELECTED_FILE_ACK_CHUNKS = 30
        private const val NO_RESPONSE_WRITE_INTERVAL_MS = 10L
        private const val MAX_FILE_PAYLOAD = 241
        private const val DESIRED_MTU = 247
        private const val SCAN_DURATION_MS = 15_000L
        private const val WRITE_RETRY_DELAY_MS = 120L
        private const val MAX_WRITE_RETRIES = 5
        private const val CONNECTION_TIMEOUT_MS = 20_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MTU_TIMEOUT_MS = 3_000L
        private const val NOTIFICATION_TIMEOUT_MS = 8_000L
        private const val INITIALIZATION_TIMEOUT_MS = 45_000L
        private const val WATCHFACE_LIST_REFRESH_DELAY_MS = 1_000L
        private const val UPLOAD_TIMEOUT_MS = 45_000L
        private const val FINALIZE_TIMEOUT_MS = 180_000L
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_SHIFT = 4
        private const val RECONNECT_MAX_DELAY_MS = 15_000L
    }
}


