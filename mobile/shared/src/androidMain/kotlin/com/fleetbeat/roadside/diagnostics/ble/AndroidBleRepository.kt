package com.fleetbeat.roadside.diagnostics.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.cancel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBleRepository(
    private val context: Context,
) : BleRepository {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val main = Handler(Looper.getMainLooper())
    private fun bluetoothEnabled(): Boolean = try { bluetoothAdapter?.isEnabled == true } catch (_: SecurityException) { false }
    private fun permissionsGranted(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private val _uiState = MutableStateFlow(initialState())
    override val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<BleTerminalLine>>(emptyList())
    override val terminalLines: StateFlow<List<BleTerminalLine>> = _terminalLines.asStateFlow()

    private val _incomingBytes =
        MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private val _writePayloadSize = MutableStateFlow(DEFAULT_MTU - 3)
    override val writePayloadSize: StateFlow<Int> = _writePayloadSize.asStateFlow()

    private var scannerCallback: ScanCallback? = null
    private var stopScanRunnableScheduled = false
    /** Reset on each connect so MTU is renegotiated (was reused from scan flag). */
    private var mtuRequestSent = false
    private var currentGatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCreditsCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCreditsCharacteristic: BluetoothGattCharacteristic? = null
    private var descriptorPhase: DescriptorPhase = DescriptorPhase.None
    private var currentDevice: BluetoothDevice? = null
    private var writePending = false
    private var payloadSize = DEFAULT_MTU - 3
        set(value) {
            field = value
            _writePayloadSize.value = value
        }
    private var writeCredits = 0
    private var readCredits = 0
    private val writeQueue = ArrayDeque<ByteArray>()
    private val devices = linkedMapOf<String, BleDevice>()

    override suspend fun refreshSystemState() {
        _uiState.update {
            it.copy(
                bluetoothAvailable = bluetoothAdapter != null,
                bluetoothEnabled = bluetoothEnabled(),
                status = normalizeStatus(it.status),
            )
        }
    }

    override suspend fun startScan() {
        refreshSystemState()
        if (!permissionsGranted()) { setError("Allow Bluetooth permissions to scan for your adapter"); return }
        if (Build.VERSION.SDK_INT < 31) {
            val location = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
                else location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!enabled) { setError("Enable Location services to scan for Bluetooth adapters on this Android version"); return }
        }
        val adapter = bluetoothAdapter ?: run {
            setError("Bluetooth LE unavailable")
            return
        }
        if (!bluetoothEnabled()) {
            _uiState.update {
                it.copy(
                    bluetoothEnabled = false,
                    status = BleConnectionStatus.Disabled,
                    isScanning = false,
                    message = "Enable Bluetooth to scan",
                )
            }
            return
        }
        if (scannerCallback != null) return
        if (_uiState.value.connectedDevice != null) { setError("Disconnect before scanning again"); return }
        devices.clear()
        syncDevices()
        appendTerminal("=== BLE scan started ===", BleTerminalDirection.HEAD)
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                main.post {
                    if (scannerCallback == null) return@post
                    if (isSupportedAdapterName(result.scanRecord?.deviceName ?: result.device.name)) {
                        updateDevice(result.device, result.rssi, advertisedName = result.scanRecord?.deviceName)
                    }
                
                }
            }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                main.post {
                    results.forEach { result ->
                        if (isSupportedAdapterName(result.scanRecord?.deviceName ?: result.device.name)) {
                            updateDevice(result.device, result.rssi, advertisedName = result.scanRecord?.deviceName)
                        }
                    }
                
                }
            }

                override fun onScanFailed(errorCode: Int) {
                main.post {
                    setError("Scan failed ($errorCode)")
                    scope.launch { stopScan() }
                
                }
            }
            }
        scannerCallback = callback
        val scanner = adapter.bluetoothLeScanner ?: run { scannerCallback = null; setError("Bluetooth scanner unavailable"); return }
        scanner.startScan(callback)
        _uiState.update {
            it.copy(
                status = if (it.connectedDevice != null) BleConnectionStatus.Connected else BleConnectionStatus.Scanning,
                isScanning = true,
                bluetoothEnabled = true,
                message = null,
            )
        }
    }

    override suspend fun stopScan() {
        val callback = scannerCallback
        if (callback != null) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scannerCallback = null
        stopScanRunnableScheduled = false
        _uiState.update {
            it.copy(
                isScanning = false,
                status =
                    when {
                        it.connectedDevice != null -> BleConnectionStatus.Connected
                        !it.bluetoothAvailable -> BleConnectionStatus.Unavailable
                        !it.bluetoothEnabled -> BleConnectionStatus.Disabled
                        it.status == BleConnectionStatus.Error -> BleConnectionStatus.Error
                        else -> BleConnectionStatus.Disconnected
                    },
            )
        }
    }

    override suspend fun connect(address: String) {
        stopScan()
        val adapter = bluetoothAdapter ?: run {
            setError("Bluetooth LE unavailable")
            return
        }
        if (!bluetoothEnabled()) {
            _uiState.update { it.copy(status = BleConnectionStatus.Disabled, message = "Enable Bluetooth first") }
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            setError("Adapter not found")
            return
        }
        disconnectGatt(closeOnly = true)
        currentDevice = device
        payloadSize = DEFAULT_MTU - 3
        mtuRequestSent = false
        writePending = false
        writeQueue.clear()
        descriptorPhase = DescriptorPhase.None
        readCharacteristic = null
        writeCharacteristic = null
        readCreditsCharacteristic = null
        writeCreditsCharacteristic = null
        _uiState.update {
            it.copy(
                status = BleConnectionStatus.Connecting,
                connectedDevice = devices[address]?.copy(isConnected = false) ?: device.asBleDevice(null, false, true),
                message = null,
            )
        }
        appendTerminal("STATUS connecting to ${device.name ?: address}", BleTerminalDirection.STATUS)
        currentGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
    }

    override suspend fun disconnect() {
        _uiState.update { it.copy(status = BleConnectionStatus.Disconnecting, message = null) }
        appendTerminal("STATUS disconnecting", BleTerminalDirection.STATUS)
        disconnectGatt(closeOnly = false)
        _uiState.update {
            it.copy(
                status = normalizeStatus(BleConnectionStatus.Disconnected),
                connectedDevice = null,
                message = null,
            )
        }
    }

    override suspend fun sendText(
        text: String,
        appendNewline: Boolean,
    ) {
        // Send exactly what the user typed. Optional CRLF only when explicitly enabled —
        // auto \r\n was arriving on the device as two extra trailing bytes.
        val payload = if (appendNewline) text + "\r\n" else text
        appendTerminal(
            if (appendNewline) "TX $text\\r\\n" else "TX $text",
            BleTerminalDirection.TX,
        )
        write(payload.toByteArray(Charsets.UTF_8))
    }

    override suspend fun sendHex(
        hex: String,
        appendNewline: Boolean,
    ) {
        val base = hexToBytes(hex)
        val payload = if (appendNewline) base + byteArrayOf(0x0D, 0x0A) else base
        appendTerminal("TX ${payload.toHexString()}", BleTerminalDirection.TX)
        write(payload)
    }

    override suspend fun sendBytes(bytes: ByteArray) {
        appendTerminal("TX ${bytes.toHexString()} (${bytes.size}b)", BleTerminalDirection.TX)
        if (bytes.isNotEmpty() && (bytes[0] == '{'.code.toByte())) {
            val text = runCatching { bytes.decodeToString() }.getOrNull()
            if (text != null && (text.contains("\"t\":\"set\"") || text.contains("\"filter\"") || text.contains("pgn_filter"))) {
                Log.i("ADAPTER-SET", text.trimEnd('\n', '\r'))
            }
        }
        
        write(bytes)
    }

    override fun close() {
        runCatching { scannerCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } }
        scannerCallback = null
        disconnectGatt(closeOnly = false)
        scope.cancel()
    }

    override fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        currentGatt = gatt
                        if (!gatt.discoverServices()) {
                            setError("discoverServices failed")
                        }
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        disconnectGatt(closeOnly = true)
                        val failedBeforeConnect = _uiState.value.status == BleConnectionStatus.Connecting
                        if (failedBeforeConnect) {
                            setError("Connection failed ($status)")
                        } else {
                            _uiState.update {
                                it.copy(
                                    status = normalizeStatus(BleConnectionStatus.Disconnected),
                                    connectedDevice = null,
                                    message = if (status == 0) null else "Connection lost ($status)",
                                )
                            }
                            if (status != 0) {
                                appendTerminal("ERROR connection lost ($status)", BleTerminalDirection.ERROR)
                            }
                        }
                    }
                }
            
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setError("Service discovery failed ($status)")
                    return@post
                }
                val profile = resolveProfile(gatt.services) ?: run {
                    setError("No serial BLE profile found")
                    return@post
                }
                readCharacteristic = profile.read
                writeCharacteristic = profile.write
                readCreditsCharacteristic = profile.readCredits
                writeCreditsCharacteristic = profile.writeCredits
                readCredits = 0
                writeCredits = if (profile.requiresCredits) 0 else Int.MAX_VALUE
                descriptorPhase =
                    if (profile.requiresCredits && readCreditsCharacteristic != null) {
                        if (!gatt.setCharacteristicNotification(readCreditsCharacteristic, true)) {
                            setError("Credits notification setup failed")
                            return@post
                        }
                        val descriptor = readCreditsCharacteristic?.getDescriptor(CCCD_UUID)
                        if (descriptor == null) {
                            setError("Credits descriptor missing")
                            return@post
                        }
                        descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) {
                            setError("Credits descriptor write failed")
                            return@post
                        }
                        DescriptorPhase.Credits
                    } else {
                        DescriptorPhase.Main.also { requestMtuOrNotifications(gatt) }
                    }
            
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    payloadSize = mtu - 3
                }
                enableMainNotifications(gatt)
            
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setError("Descriptor write failed")
                    return@post
                }
                when (descriptorPhase) {
                    DescriptorPhase.Credits -> {
                        descriptorPhase = DescriptorPhase.Main
                        requestMtuOrNotifications(gatt)
                    }
                    DescriptorPhase.Main -> {
                        descriptorPhase = DescriptorPhase.None
                        completeConnection(gatt)
                    }
                    DescriptorPhase.None -> Unit
                }
            
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val notificationValue = characteristic.value?.copyOf()

                main.post {
                    if (gatt !== currentGatt) return@post

                when (characteristic.uuid) {
                    readCreditsCharacteristic?.uuid -> {
                        val delta = notificationValue?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        writeCredits += delta
                        flushWriteQueue()
                    }
                    readCharacteristic?.uuid -> {
                        val value = notificationValue ?: return@post
                        handleIncoming(value)
                        if (writeCreditsCharacteristic != null) {
                            grantReadCredits(gatt)
                        }
                    }
                }
            
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setError("Write failed")
                    return@post
                }
                if (characteristic.uuid == writeCharacteristic?.uuid && writeCredits != Int.MAX_VALUE && writeCredits > 0) {
                    writeCredits -= 1
                }
                writePending = false
                flushWriteQueue()
            
                }
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                main.post {
                    if (gatt !== currentGatt) return@post

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    updateDevice(gatt.device, rssi, markConnected = true)
                }
            
                }
            }
        }

    private fun requestMtuOrNotifications(gatt: BluetoothGatt) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!mtuRequestSent) {
                mtuRequestSent = true
                if (gatt.requestMtu(MAX_MTU)) return
            }
        }
        enableMainNotifications(gatt)
    }

    private fun enableMainNotifications(gatt: BluetoothGatt) {
        val read = readCharacteristic ?: run {
            setError("Read characteristic missing")
            return
        }
        val write = writeCharacteristic ?: run {
            setError("Write characteristic missing")
            return
        }
        if ((write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            setError("Write characteristic is not writable")
            return
        }
        if (!gatt.setCharacteristicNotification(read, true)) {
            setError("Notification setup failed")
            return
        }
        val descriptor = read.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            setError("CCCD descriptor missing")
            return
        }
        descriptor.value =
            when {
                (read.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                (read.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> {
                    setError("Characteristic cannot notify")
                    return
                }
            }
        if (!gatt.writeDescriptor(descriptor)) {
            setError("Main descriptor write failed")
        }
    }

    private fun completeConnection(gatt: BluetoothGatt) {
        if (writeCreditsCharacteristic != null) {
            readCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            grantReadCredits(gatt)
        }
        updateDevice(gatt.device, uiState.value.connectedDevice?.rssi, markConnected = true)
        _uiState.update {
            it.copy(
                status = BleConnectionStatus.Connected,
                connectedDevice = devices[gatt.device.address]?.copy(isConnected = true, isRemembered = true),
                message = null,
            )
        }
        appendTerminal("STATUS connected to ${gatt.device.name ?: gatt.device.address}", BleTerminalDirection.STATUS)
        gatt.readRemoteRssi()
    }

    private fun grantReadCredits(gatt: BluetoothGatt) {
        val writeCreditsChar = writeCreditsCharacteristic ?: return
        if (readCredits > 0) readCredits -= 1
        if (readCredits <= MIN_READ_CREDITS) {
            val add = MAX_READ_CREDITS - readCredits
            readCredits += add
            writeCreditsChar.value = byteArrayOf(add.toByte())
            gatt.writeCharacteristic(writeCreditsChar)
        }
    }

    private fun handleIncoming(data: ByteArray) {
        // Always hex for CAN (0x01…); JSON stays readable text.
        val line =
            if (data.isNotEmpty() && data[0] == 0x01.toByte()) {
                
                "RX ${data.toHexString()} (${data.size}b)"
            } else {
                val text = data.toPrintableText()
                if (text.contains("\"filter\"") || text.contains("pgn_filter") || text.contains("\"t\":\"status\"")) {
                    Log.i("ADAPTER-STATUS", text.trimEnd('\n', '\r'))
                }
                "RX $text"
            }
        appendTerminal(line, BleTerminalDirection.RX)
        _incomingBytes.tryEmit(data.copyOf())
    }

    private fun write(bytes: ByteArray) {
        if (_uiState.value.status != BleConnectionStatus.Connected) {
            throw IOException("Adapter not connected")
        }
        if (bytes.firstOrNull() == '{'.code.toByte() && bytes.size > payloadSize) {
            throw IOException("Adapter MTU is too small for configuration. Reconnect the adapter.")
        }
        // Firmware has no reassembly: never split JSON objects or individual 14-byte CAN frames.
        enqueueAtomic(bytes)
        flushWriteQueue()
    }

    private fun enqueueAtomic(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val max = payloadSize.coerceAtLeast(1)
        when {
            bytes[0] == '{'.code.toByte() || bytes[0] == 0x7B.toByte() -> {
                // Whole JSON in one ATT write (requires negotiated MTU ≥ payload).
                writeQueue.addLast(bytes)
            }
            bytes[0] == 0x01.toByte() -> {
                var off = 0
                while (off < bytes.size) {
                    if (bytes[off] == 0x01.toByte() && off + 14 <= bytes.size) {
                        writeQueue.addLast(bytes.copyOfRange(off, off + 14))
                        off += 14
                    } else {
                        // Trailing partial / unknown — one chunk, best effort.
                        writeQueue.addLast(bytes.copyOfRange(off, bytes.size))
                        break
                    }
                }
            }
            else -> bytes.asChunks(max).forEach { writeQueue.addLast(it) }
        }
    }

    private fun flushWriteQueue() {
        if (writePending) return
        if (writeCredits == 0) return
        val gatt = currentGatt ?: return
        val write = writeCharacteristic ?: return
        val next = writeQueue.removeFirstOrNull() ?: return
        writePending = true
        write.value = next
        if (!gatt.writeCharacteristic(write)) {
            writePending = false
            setError("Write failed")
        }
    }

    private fun updateDevice(
        device: BluetoothDevice,
        rssi: Int?,
        markConnected: Boolean = false,
        advertisedName: String? = null,
    ) {
        val name = (advertisedName ?: device.name)?.takeIf { it.isNotBlank() } ?: devices[device.address]?.name
        if (!markConnected && !isSupportedAdapterName(name)) return
        val existing = devices[device.address]
        val connectedAddress = if (markConnected) device.address else _uiState.value.connectedDevice?.address
        devices[device.address] =
            BleDevice(
                address = device.address,
                name = name ?: "Unnamed BLE",
                rssi = rssi ?: existing?.rssi,
                signalStrength = mapRssiToBars(rssi ?: existing?.rssi),
                isConnected = connectedAddress == device.address,
                isRemembered = existing?.isRemembered == true || connectedAddress == device.address,
            )
        syncDevices()
    }

    private fun isSupportedAdapterName(name: String?): Boolean =
        name?.startsWith(ADAPTER_NAME_PREFIX, ignoreCase = true) == true

    private fun syncDevices() {
        val connectedAddress = _uiState.value.connectedDevice?.address
        val sorted =
            devices.values
                .map { device -> device.copy(isConnected = device.address == connectedAddress || device.isConnected) }
                .sortedWith(
                    compareByDescending<BleDevice> { it.isConnected }
                        .thenByDescending { it.isRemembered }
                        .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.address },
                )
        _uiState.update { it.copy(devices = sorted) }
    }

    private fun disconnectGatt(closeOnly: Boolean) {
        try {
            currentGatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            currentGatt?.close()
        } catch (_: Exception) {
        }
        currentGatt = null
        readCharacteristic = null
        writeCharacteristic = null
        readCreditsCharacteristic = null
        writeCreditsCharacteristic = null
        descriptorPhase = DescriptorPhase.None
        writeQueue.clear()
        writePending = false
        writeCredits = 0
        readCredits = 0
        payloadSize = DEFAULT_MTU - 3
        if (!closeOnly) currentDevice = null
    }

    private fun setError(message: String) {
        appendTerminal("ERROR $message", BleTerminalDirection.ERROR)
        disconnectGatt(closeOnly = true)
        _uiState.update {
            it.copy(
                status = BleConnectionStatus.Error,
                isScanning = false,
                connectedDevice = null,
                message = message,
            )
        }
    }

    private fun appendTerminal(
        text: String,
        direction: BleTerminalDirection,
    ) {
        _terminalLines.update { lines ->
            (lines + BleTerminalLine(text = text, direction = direction)).takeLast(400)
        }
    }

    private fun initialState(): BleUiState =
        BleUiState(
            bluetoothAvailable = bluetoothAdapter != null,
            bluetoothEnabled = bluetoothEnabled(),
            status =
                when {
                    bluetoothAdapter == null -> BleConnectionStatus.Unavailable
                    bluetoothEnabled() -> BleConnectionStatus.Disconnected
                    else -> BleConnectionStatus.Disabled
                },
        )

    private fun normalizeStatus(status: BleConnectionStatus): BleConnectionStatus =
        when {
            bluetoothAdapter == null -> BleConnectionStatus.Unavailable
            !bluetoothEnabled() -> BleConnectionStatus.Disabled
            status == BleConnectionStatus.Unavailable || status == BleConnectionStatus.Disabled -> BleConnectionStatus.Disconnected
            else -> status
        }

    private fun resolveProfile(services: List<BluetoothGattService>): SerialProfile? {
        services.firstOrNull { it.uuid == CC254X_SERVICE_UUID }?.let { service ->
            val characteristic = service.getCharacteristic(CC254X_CHAR_RW_UUID) ?: return null
            return SerialProfile(read = characteristic, write = characteristic)
        }
        services.firstOrNull { it.uuid == RN4870_SERVICE_UUID }?.let { service ->
            val characteristic = service.getCharacteristic(RN4870_CHAR_RW_UUID) ?: return null
            return SerialProfile(read = characteristic, write = characteristic)
        }
        services.firstOrNull { it.uuid == NRF_SERVICE_UUID }?.let { service ->
            val rw2 = service.getCharacteristic(NRF_CHAR_RW2_UUID)
            val rw3 = service.getCharacteristic(NRF_CHAR_RW3_UUID)
            if (rw2 != null && rw3 != null) {
                val rw2write = (rw2.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                val rw3write = (rw3.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                return when {
                    rw2write && !rw3write -> SerialProfile(read = rw3, write = rw2)
                    rw3write && !rw2write -> SerialProfile(read = rw2, write = rw3)
                    else -> null
                }
            }
        }
        services.firstOrNull { it.uuid == TIO_SERVICE_UUID }?.let { service ->
            val read = service.getCharacteristic(TIO_CHAR_RX_UUID) ?: return null
            val write = service.getCharacteristic(TIO_CHAR_TX_UUID) ?: return null
            val readCredits = service.getCharacteristic(TIO_CHAR_RX_CREDITS_UUID) ?: return null
            val writeCredits = service.getCharacteristic(TIO_CHAR_TX_CREDITS_UUID) ?: return null
            return SerialProfile(
                read = read,
                write = write,
                readCredits = readCredits,
                writeCredits = writeCredits,
                requiresCredits = true,
            )
        }
        return null
    }

    private fun BluetoothDevice.asBleDevice(
        rssi: Int?,
        connected: Boolean,
        remembered: Boolean,
    ): BleDevice =
        BleDevice(
            address = address,
            name = name?.takeIf { it.isNotBlank() } ?: "Unnamed BLE",
            rssi = rssi,
            signalStrength = mapRssiToBars(rssi),
            isConnected = connected,
            isRemembered = remembered,
        )

    private fun mapRssiToBars(rssi: Int?): Int =
        when {
            rssi == null -> 0
            rssi >= -55 -> 4
            rssi >= -67 -> 3
            rssi >= -78 -> 2
            rssi >= -90 -> 1
            else -> 0
        }

    private fun hexToBytes(source: String): ByteArray {
        val clean = source.replace(" ", "").replace("\n", "").replace("\r", "")
        require(clean.length % 2 == 0) { "Hex string must contain even number of characters" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toPrintableText(): String {
        val text = toString(Charsets.UTF_8).replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\r")
        return if (text.any { it.code in 32..126 || it == '\\' }) text else "${toHexString()} [hex]"
    }

    private fun ByteArray.asChunks(size: Int): List<ByteArray> {
        if (isEmpty()) return listOf(byteArrayOf())
        if (this.size <= size) return listOf(this)
        val result = mutableListOf<ByteArray>()
        var index = 0
        while (index < this.size) {
            val end = (index + size).coerceAtMost(this.size)
            result += copyOfRange(index, end)
            index = end
        }
        return result
    }

    private data class SerialProfile(
        val read: BluetoothGattCharacteristic,
        val write: BluetoothGattCharacteristic,
        val readCredits: BluetoothGattCharacteristic? = null,
        val writeCredits: BluetoothGattCharacteristic? = null,
        val requiresCredits: Boolean = false,
    )

    private enum class DescriptorPhase {
        None,
        Credits,
        Main,
    }

    companion object {
        private const val ADAPTER_NAME_PREFIX = "ITD_ELD"
        private const val MAX_MTU = 512
        private const val DEFAULT_MTU = 23
        private const val MIN_READ_CREDITS = 16
        private const val MAX_READ_CREDITS = 64

        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val CC254X_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CC254X_CHAR_RW_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val NRF_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NRF_CHAR_RW2_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NRF_CHAR_RW3_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val RN4870_SERVICE_UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        private val RN4870_CHAR_RW_UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        private val TIO_SERVICE_UUID = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB")
        private val TIO_CHAR_TX_UUID = UUID.fromString("00000001-0000-1000-8000-008025000000")
        private val TIO_CHAR_RX_UUID = UUID.fromString("00000002-0000-1000-8000-008025000000")
        private val TIO_CHAR_TX_CREDITS_UUID = UUID.fromString("00000003-0000-1000-8000-008025000000")
        private val TIO_CHAR_RX_CREDITS_UUID = UUID.fromString("00000004-0000-1000-8000-008025000000")
    }
}
