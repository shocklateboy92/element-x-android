/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

class WebViewAudioManager(
    private val webView: WebView,
) {
    // The list of device types that are considered as communication devices, sorted by likelihood of it being used for communication.
    private val wantedDeviceTypes = listOf(
        // Paired bluetooth device with microphone
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        // USB devices which can play or record audio
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        // Wired audio devices
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        // The built-in speaker of the device
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        // The built-in earpiece of the device
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )

    private val audioManager = webView.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val commsDeviceChangedListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (device?.id == expectedNewCommunicationDeviceId) {
            if (device != null) {
                expectedNewCommunicationDeviceId = null
                Timber.d("Audio device changed, type: ${device.type}")
                selectAudioDeviceInWebView(device.id.toString())
            } else {
                Timber.d("No audio device selected")
            }
        } else {
            // We were expecting a device change but it didn't happen, so we should retry
            val expectedDeviceId = expectedNewCommunicationDeviceId
            if (expectedDeviceId != null) {
                // Remove the expected id so we only retry once
                expectedNewCommunicationDeviceId = null
                audioManager.selectAudioDevice(expectedDeviceId.toString())
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            setAvailableAudioDevices()
            // TODO: maybe only change the selected device to a new external one
            selectDefaultAudioDevice()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            setAvailableAudioDevices()
            // TODO: maybe only change the selected device if it was one of the added devices
            selectDefaultAudioDevice()
        }
    }

    private var expectedNewCommunicationDeviceId: Int? = null

    val isInCallMode = AtomicBoolean(false)

    init {
        registerWebViewDeviceSelectedCallback()
    }

    fun onCallStarted() {
        if (!isInCallMode.compareAndSet(false, true)) {
            Timber.w("Audio: tried to enable webview in-call audio mode while already in it")
            return
        }

        Timber.d("Audio: enabling webview in-call audio mode")

        // TODO: double check used audio stream
        audioManager.mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Set 'voice call' mode so volume keys actually control the call volume
            AudioManager.MODE_IN_COMMUNICATION
        } else {
            // Workaround for Android 12 and lower, otherwise changing the audio device doesn't work
            AudioManager.MODE_NORMAL
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(Executors.newSingleThreadExecutor(), commsDeviceChangedListener)
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        setAvailableAudioDevices()
        selectDefaultAudioDevice()
        setWebViewOnAudioDeviceSelectedCallback()
    }

    fun onCallStopped() {
        if (!isInCallMode.compareAndSet(true, false)) {
            Timber.w("Audio: tried to disable webview in-call audio mode while already disabled")
            return
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnCommunicationDeviceChangedListener(commsDeviceChangedListener)
        }

        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun setWebViewOnAudioDeviceSelectedCallback() {
        Timber.d("Adding callback in controls.onOutputDeviceSelect")
        webView.evaluateJavascript("controls.onOutputDeviceSelect = (id) => { onAudioDeviceSelectedCallback.setOutputDevice(id); };", null)
    }

    private fun setAvailableAudioDevices() {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.map(CompatAudioDevice::fromAudioDeviceInfo)
        } else {
            val rawAudioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            rawAudioDevices.filter { it.type in wantedDeviceTypes && it.isSink }.map { CompatAudioDevice.fromAudioDeviceInfo(it) }
        }
        Timber.d("Updating available audio devices")
        val deviceList = devices.joinToString(",") { "{ 'id': '${it.id}', 'name': '${deviceName(it.type, it.name)}' }" }
        webView.evaluateJavascript("controls.setAvailableOutputDevices([$deviceList]);", {
            Timber.d("Audio: setAvailableOutputDevices result: $it")
        })
    }

    private fun registerWebViewDeviceSelectedCallback() {
        val webViewAudioDeviceSelectedCallback = WebViewAudioOutputCallback {
            Timber.d("Audio device selected in webview, id: $it")
            audioManager.selectAudioDevice(it)
        }
        Timber.d("Setting onAudioDeviceSelectedCallback javascript interface in webview")
        webView.addJavascriptInterface(webViewAudioDeviceSelectedCallback, "onAudioDeviceSelectedCallback")
    }

    @Suppress("DEPRECATION")
    private fun selectDefaultAudioDevice() {
        val selectedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            devices.minByOrNull {
                wantedDeviceTypes.indexOf(it.type).let { index ->
                    // If the device type is not in the wantedDeviceTypes list, we give it a low priority
                    if (index == -1) Int.MAX_VALUE else index
                }
            }
        } else {
            // If we don't have access to the new APIs, use the deprecated ones
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            devices.filter { it.isSink }
                .minByOrNull {
                    wantedDeviceTypes.indexOf(it.type).let { index ->
                        // If the device type is not in the wantedDeviceTypes list, we give it a low priority
                        if (index == -1) Int.MAX_VALUE else index
                    }
                }
        }

        expectedNewCommunicationDeviceId = selectedDevice?.id
        audioManager.selectAudioDevice(selectedDevice)

        selectedDevice?.let {
            selectAudioDeviceInWebView(it.id.toString())
        } ?: run {
            Timber.w("Audio: unable to select default audio device")
        }
    }

    private fun selectAudioDeviceInWebView(deviceId: String) {
        MainScope().launch { webView.evaluateJavascript("controls.setOutputDevice('$deviceId');", null) }
    }
}

private fun AudioManager.selectAudioDevice(device: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val audioDevice = availableCommunicationDevices.find { it.id.toString() == device }
        selectAudioDevice(audioDevice)
    } else {
        val rawAudioDevices = getDevices(AudioManager.GET_DEVICES_ALL)
        val audioDevice = rawAudioDevices.find { it.id.toString() == device }
        selectAudioDevice(audioDevice)
    }
}

private fun AudioManager.selectAudioDevice(device: AudioDeviceInfo?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (device != null) {
            setCommunicationDevice(device)
        } else {
            Timber.w("Audio: unable to select audio device with id: ${device?.id}")
        }
    } else {
        if (device != null) {
            isSpeakerphoneOn = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            isBluetoothScoOn = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } else {
            Timber.w("Audio: unable to select audio device with id: ${device?.id}")
        }
    }
}

private class WebViewAudioOutputCallback(
    private val callback: (String) -> Unit,
) {
    @JavascriptInterface
    fun setOutputDevice(id: String) {
        Timber.d("Audio device selected in webview, id: $id")
        callback(id)

    }
}

private fun deviceName(type: Int, name: String): String {
    val typePart = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        else -> "Unknown device type: $type"
    }
    return if (isBuiltIn(type)) {
        typePart
    } else {
        val namePart = if (name.length > 10) {
            name.substring(0, 10) + "…"
        } else {
            name
        }
        "$namePart - $typePart"
    }
}

private fun isBuiltIn(type: Int): Boolean = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> true
    else -> false
}


data class CompatAudioDevice(
    val id: String,
    val name: String,
    val type: Int,
) {
    companion object {
        fun fromAudioDeviceInfo(audioDeviceInfo: AudioDeviceInfo): CompatAudioDevice {
            return CompatAudioDevice(
                id = audioDeviceInfo.id.toString(),
                name = deviceName(type = audioDeviceInfo.type, name = audioDeviceInfo.productName.toString()),
                type = audioDeviceInfo.type,
            )
        }
    }
}
