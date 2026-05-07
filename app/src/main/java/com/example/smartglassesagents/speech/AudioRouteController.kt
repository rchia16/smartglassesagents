package com.example.smartglassesagents.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class AudioRouteController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var lastDevices: List<AudioDeviceInfo> = emptyList()

    private val _state = MutableStateFlow(AudioRouteState())
    val state: StateFlow<AudioRouteState> = _state

    fun refresh() {
        val permissionGranted = hasBluetoothConnectPermission()
        try {
            lastDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val outputs = lastDevices.map { it.toOutputDevice() }
            _state.update {
                it.copy(
                    outputs = outputs,
                    bluetoothPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    bluetoothPermissionGranted = permissionGranted,
                    activeCommunicationDeviceName = activeCommunicationDeviceName(),
                    recentError = null
                )
            }
        } catch (error: SecurityException) {
            _state.update {
                it.copy(
                    bluetoothPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    bluetoothPermissionGranted = false,
                    recentError = error.message ?: "Bluetooth audio route permission denied."
                )
            }
        }
    }

    fun preferBluetoothOutput(): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            throw IllegalStateException("Bluetooth Connect permission is required to inspect audio routes.")
        }

        refresh()
        val device = lastDevices.firstOrNull { it.isPreferredMediaBluetoothOutput() }
            ?: lastDevices.firstOrNull { it.isBluetoothOutput() }
            ?: throw IllegalStateException("No Bluetooth audio output is connected.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val routed = audioManager.setCommunicationDevice(device)
            if (!routed) {
                throw IllegalStateException("Android did not accept ${device.displayLabel()} as the communication route.")
            }
        }

        val label = device.displayLabel()
        _state.update {
            it.copy(
                preferredOutputName = label,
                activeCommunicationDeviceName = activeCommunicationDeviceName() ?: label,
                recentError = null
            )
        }
        label
    }.onFailure { error ->
        _state.update { it.copy(recentError = error.message ?: "Failed to prefer Bluetooth audio.") }
    }

    fun clearPreferredOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        _state.update {
            it.copy(
                preferredOutputName = null,
                activeCommunicationDeviceName = activeCommunicationDeviceName(),
                recentError = null
            )
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    private fun activeCommunicationDeviceName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.displayLabel()
        } else {
            null
        }
}

data class AudioRouteState(
    val outputs: List<AudioOutputDevice> = emptyList(),
    val preferredOutputName: String? = null,
    val activeCommunicationDeviceName: String? = null,
    val bluetoothPermissionRequired: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    val bluetoothPermissionGranted: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
    val recentError: String? = null
) {
    val bluetoothOutputs: List<AudioOutputDevice> = outputs.filter { it.isBluetooth }
}

data class AudioOutputDevice(
    val id: Int,
    val label: String,
    val typeLabel: String,
    val isBluetooth: Boolean
)

private fun AudioDeviceInfo.toOutputDevice(): AudioOutputDevice =
    AudioOutputDevice(
        id = id,
        label = displayLabel(),
        typeLabel = typeLabel(),
        isBluetooth = isBluetoothOutput()
    )

private fun AudioDeviceInfo.displayLabel(): String =
    productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel()

private fun AudioDeviceInfo.isBluetoothOutput(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

private fun AudioDeviceInfo.isPreferredMediaBluetoothOutput(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

private fun AudioDeviceInfo.typeLabel(): String =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (type) {
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
                else -> "Audio output"
            }
        } else {
            "Audio output"
        }
    }
