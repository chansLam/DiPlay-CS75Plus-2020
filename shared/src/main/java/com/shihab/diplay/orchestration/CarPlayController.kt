package com.shihab.diplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import com.shihab.diplay.compat.BluetoothCompat
import com.shihab.diplay.media.musicVolumeWarning
import com.shihab.diplay.bluetooth.BluetoothAddressResolver
import com.shihab.diplay.bluetooth.CarPlayBluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.airplay.AirPlayTcpAccepted
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayContact
import com.shihab.diplay.airplay.AirPlayKnobState
import com.shihab.diplay.airplay.AirPlayDeviceInfo
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.airplay.VideoInCar
import com.shihab.diplay.airplay.VideoPlaybackDelivery
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.mfi.Iap2MfiAuthenticationClient
import com.shihab.diplay.mfi.MfiAuthenticationClient
import com.shihab.diplay.mfi.RemoteMfiAuthenticationClient
import com.shihab.diplay.mfi.LocalMfiAuthenticationClient
import com.shihab.diplay.network.CarPlayBonjour
import com.shihab.diplay.network.diagnosticSummary
import com.shihab.diplay.network.CarPlayWirelessService
import com.shihab.diplay.network.LegacyWifiP2pGroupManager
import com.shihab.diplay.network.LocalOnlyHotspotManager
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.WifiScanPause
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotBackend
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessInterfaceDiagnostics
import com.shihab.diplay.network.WirelessReceiveDiagnostics
import com.shihab.diplay.network.WirelessStartupPolicy
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupDiagnostics
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.BluetoothRfcommDuplexStream
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LocationProvider
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessControlClient
import com.shihab.diplay.transport.Iap2WirelessControlResult
import com.shihab.diplay.transport.Iap2WirelessControlTerminal
import com.shihab.diplay.transport.Iap2WirelessIdentification
import com.shihab.diplay.transport.Iap2WirelessLinkRole
import com.shihab.diplay.transport.forWirelessLink
import com.shihab.diplay.transport.LinuxI2cTransport
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
    startSessionSent: Boolean = false,
): Boolean = handoffRequested || tunnelActive || sessionActive || startSessionSent

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * Reusable lifecycle entry points: [start], [close] (stop), [reconnectMfi], [reconnectIphone].
 * New call sites may wrap this with [CarPlaySessionFacade]; hosts may keep a direct reference.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
@android.annotation.SuppressLint("MissingPermission")
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shihab.diplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        WifiScanPause.restoreIfNeeded(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val bluetoothAdapter = BluetoothCompat.getAdapter(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val availabilityPollGeneration = AtomicInteger(0)
    private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shihab.diplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shihab.diplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var wifiScanPause: WifiScanPause? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: CarPlayWirelessService? = null
    @Volatile private var vpnBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    /** Set when the handoff fell back and kept Bluetooth as the only iAP2 channel. */
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayWirelessService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IOException("CarPlay VPN service disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            val replacement = activeSession !== session
            if (replacement) {
                com.shihab.diplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            if (config.transport == CarPlayTransport.WIRELESS) {
                val output = runCatching {
                    @Suppress("DEPRECATION")
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    if (audio == null) "audioManager=unavailable" else {
                        @Suppress("DEPRECATION")
                        "a2dpOn=${audio.isBluetoothA2dpOn} musicActive=${audio.isMusicActive} " +
                            "musicVolume=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/" +
                            "${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
                    }
                }.getOrElse { "audioDiagnostics=${it.javaClass.simpleName}" }
                debugLog("wireless audio route at AirPlay session start: $output; " +
                    "OEM A2DP/MCU source selection may differ from Android routing APIs")
                // Issue #88: near-zero STREAM_MUSIC volume is the most common "packets flow
                // but no sound" cause on head units; surface it loudly at session start.
                runCatching {
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    musicVolumeWarning(audio)?.let { debugLog(it) }
                }
            }
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: host opens an in-session system-app panel
        // (overlay) so CarPlay does not leave the foreground Activity.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; notifying host for system app panel")
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            val deviceId = params["deviceID"] as? String ?: params["deviceIdentifier"] as? String
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")} deviceId=${if (deviceId != null) "[address]" else "none"}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                // Issue #128: on head units with native Bluetooth music, the iPhone is often
                // also paired with that A2DP sink. iOS then resolves audio away from CarPlay
                // (kept on the phone) and never opens the AirPlay audio stream. This hint makes
                // the conflict visible in the report; the fix is user-side (forget the native
                // BT pairing), the app cannot re-route iOS audio.
                debugLog(
                    "wireless audio route hint: if this iPhone is also paired with the head " +
                        "unit's native Bluetooth music, iOS may keep audio on the phone and " +
                        "never request the CarPlay audio stream; disconnect/forget that " +
                        "pairing so CarPlay is the only audio route",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for wheel controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        startMfi()
    }

    /** Reopens the MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else {
            restartWireless()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shihab.diplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** Dashboard map routing was BYD-cluster specific; SoftAP CS75 Plus has no cluster output. */
    fun dashboardMapRoute(): Any? = null

    /** Whether the wheel can currently control the visible dashboard map. */
    fun dashboardMapStreaming(): Boolean = dashboardMapRoute() != null

    /** One zoom step for the dashboard map, as the car's own zoom controls send it. */
    fun zoomDashboardMap(zoomIn: Boolean): Boolean {
        val route = dashboardMapRoute() ?: return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session && dashboardMapRoute() == route) session.changeMapZoomLevel(zoomIn)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            dashboardMapOutputVisible = false
        }
        firstTcpWatchdog?.terminate()
        startupTimer.shutdownNow()
        (hotspot as? ManualHotspotManager)?.close()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    closeBestEffort("Wi-Fi scan pause") { wifiScanPause?.close() }
                    wifiScanPause = null
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The wheel zoom follows what the dashboard shows now, so zoom mode starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.
    private fun applyClusterUi(shown: Boolean) = synchronized(clusterUiLock) {
        val session = activeSession ?: return@synchronized
        val stream = session.clusterStream.takeIf { it > 0 } ?: return@synchronized
        if (clusterUiStream != session to stream) {
            clusterUiStream = session to stream
            clusterUiShown = true
            clusterUiVisibility = (session to stream) to true
        }
        if (shown == clusterUiShown && (!shown || session.clusterUrl() != null)) return@synchronized
        if (session.setClusterUiShown(shown)) {
            clusterUiShown = shown
            clusterUiVisibility = (session to stream) to shown
            dashboardMapEpoch.incrementAndGet()
            debugLog("Cluster map: ${if (shown) "showUI, the cluster shows the map" else "stopUI, the cluster hides the map"}")
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onRouteFrame(frame: com.shihab.diplay.iap2.wire.Iap2Frame) {
        com.shihab.diplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shihab.diplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val privateRoot = appContext.filesDir
        val offlineDirectory = java.io.File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI, attempt = 0) {
            when (config.mfiTarget) {
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        startWireless()
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        executor.execute { runWireless(generation) }
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        Thread(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        var stage = "hotspot startup"
        try {
            debugLog("wireless bring-up generation=$generation starting")
            // Issue #79: without a Bluetooth adapter, wireless CarPlay is impossible —
            // iPhone needs Bluetooth for discovery/pairing. Fail fast instead of waiting
            // 60s for a Wi-Fi connection that will never come.
            // Issue #138: gate on the hybrid adapter (vendor-private Bluetooth service +
            // standard Android), not the raw Android adapter alone — on some head units
            // (e.g. ECARX E01) Bluetooth only works through the vendor service while
            // getDefaultAdapter() reports null or disabled.
            val hybridBt = CarPlayBluetoothManager.get(appContext)
            if (!hybridBt.isSupported()) {
                throw IOException(
                    "No Bluetooth adapter on this head unit; wireless CarPlay requires Bluetooth " +
                        "for iPhone discovery."
                )
            }
            // Issue #117: adapter present but switched off — the iPhone can never discover
            // the head unit, so fail fast with an actionable message instead of the 60s
            // Wi-Fi wait ending in "Timed out waiting for iPhone to connect over Wi-Fi hotspot".
            // Issue #165: try enabling Bluetooth first — some head units start with BT off and
            // would otherwise never get past this check.
            if (!hybridBt.isEnabled()) {
                runCatching { hybridBt.enable() }
                val enableDeadline = System.currentTimeMillis() + 3000L
                while (!hybridBt.isEnabled() && System.currentTimeMillis() < enableDeadline) {
                    try {
                        Thread.sleep(200L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            if (!hybridBt.isEnabled()) {
                throw IOException(
                    "Bluetooth is turned off on this head unit; wireless CarPlay requires Bluetooth " +
                        "for iPhone discovery. Turn on Bluetooth in the head unit system settings " +
                        "and retry."
                )
            }
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = FirstTcpWatchdog(
                listener = listenerIdentity,
                schedule = { delay, action ->
                    val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    val cancel: () -> Unit = { future.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && generation == wirelessGeneration.get()) {
                        fail(WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                            "No AirPlay TCP after CarPlay StartSession"), generation)
                        Thread({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
                log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
            )
            firstTcpWatchdog = watchdog
            val mfi = mfiSession?.client
                ?: throw IOException("MFi coprocessor client is unavailable")
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                return
            }
            pauseWifiScans(hotspotInfo.backend)
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val deviceIdentifier = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)
            stage = "Bluetooth device selection"

            val adapter = bluetoothAdapter
            val hybridAdapter = CarPlayBluetoothManager.get(appContext)
            // BT off already failed fast at bring-up start; do not burn seconds on enable().
            val device = try {
                if (adapter != null && (adapter.isEnabled || hybridAdapter.isEnabled())) {
                    selectWirelessBluetoothDevice(adapter)
                } else {
                    null
                }
            } catch (error: Throwable) {
                debugLog("wireless Bluetooth device selection not available: ${error.message}; proceeding with Wi-Fi discovery")
                null
            }
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless selected Bluetooth target targetAddress=${device?.address ?: "none"} localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            stage = "AirPlay listener startup"
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        // KitKat Bonjour is single-family; extra accept threads cost RAM/CPU with no discovery gain.
                        additionalBindAddresses = emptyList(),
                    )
                ) {
                    is CarPlayWirelessService.AttachResult.Started -> Unit
                    CarPlayWirelessService.AttachResult.AlreadyStarted ->
                        throw IOException("Wireless AirPlay transport is already attached")
                    is CarPlayWirelessService.AttachResult.Failed ->
                        throw IOException(result.message)
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = listOf(hostAddressText),
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            stage = "Bonjour discovery startup"
            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                // Bind discovery and its connect probe to the same AP/address family as AirPlay.
                // The car hotspot previously used system NSD, which could resolve another interface
                // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
                // Some KitKat head units have no working servicediscovery system service.
                // Use bundled JmDNS instead of crashing in NsdManager.getMessenger().
                useInterfaceMdns = com.shihab.diplay.network.useBundledCarPlayMdns(
                    com.shihab.diplay.compat.realSdkInt(),
                ),
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                additionalAddresses = emptyList(),
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
                bonjourClient.start()
            }
            startedBonjour = bonjourClient
            diagnostics.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            if (device != null) {
                onStatus(CarPlayStatus.ConnectingBluetooth)
                stage = "Bluetooth RFCOMM connection"
                debugLog(
                    "wireless RFCOMM connecting address=${device.address} " +
                        "uuid=$IAP2_IPHONE_UUID",
                )
                logBluetoothConnectionSnapshot(device, "before-connect")
                val bluetoothStarted = System.nanoTime()

                var stream: BlockingDuplexByteStream? = null
                var channel: Iap2Session? = null
                var controlResult: Iap2WirelessControlResult? = null
                var lastBootstrapError: Throwable? = null
                var attempt = 0
                val maxAttempts = 2

                while (attempt < maxAttempts && !isStaleWirelessRun(generation)) {
                    attempt++
                    if (activeSession != null || wirelessActiveReported.get()) {
                        debugLog("AirPlay session active over Wi-Fi; continuing without Bluetooth bootstrap")
                        break
                    }
                    try {
                        onStatus(CarPlayStatus.ConnectingBluetooth)
                        stage = "Bluetooth RFCOMM connection"
                        debugLog("wireless RFCOMM connecting (attempt $attempt/$maxAttempts) address=${device.address} uuid=$IAP2_IPHONE_UUID")
                        logBluetoothConnectionSnapshot(device, "before-connect-$attempt")
                        val currentStream = hybridAdapter.openRfcommStream(device.address, IAP2_IPHONE_UUID)
                        bluetoothStream = currentStream
                        stream = currentStream
                        debugLog("wireless RFCOMM connected address=${device.address}")

                        val currentChannel = Iap2Session.openWireless(
                            currentStream,
                            traceContext = "wireless-rfcomm",
                            onTrace = ::debugLog,
                            onArtwork = ::onArtworkTransfer,
                        )
                        synchronized(wirelessResourceLock) {
                            if (isStaleWirelessRun(generation)) {
                                currentChannel.close()
                                return
                            }
                            csm = currentChannel
                            channel = currentChannel
                        }
                        debugLog("wireless iAP2 CSM channel opened over RFCOMM")
                        if (isStaleWirelessRun(generation)) return

                        onStatus(CarPlayStatus.RunningWireless)
                        debugLog(
                            "wireless Bluetooth iAP2 bootstrap starting " +
                                "location=false vehicleStatus=false",
                        )
                        startedHotspot?.validateReady()
                        val result = Iap2WirelessControlClient(
                            session = currentChannel,
                            mfi = Iap2MfiAuthenticationClient(mfi),
                        ).run(
                            identification = bootstrapIdentification,
                            endpoint = endpoint,
                            timeoutMillis = WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS,
                            keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                            beforeStartSession = { startedHotspot?.validateReady() },
                            onStartSessionSent = { watchdog.startSessionSent(it.sentAtNanos) },
                            onIncoming = ::onRouteFrame,
                            onProgress = { message ->
                                diagnostics.controlProgress(message)
                                debugLog(message)
                            },
                        )
                        controlResult = result
                        break
                    } catch (e: Throwable) {
                        lastBootstrapError = e
                        debugLog("wireless Bluetooth bootstrap attempt $attempt failed: ${e.message}")
                        runCatching { channel?.close() }
                        channel = null
                        runCatching { stream?.close() }
                        stream = null
                        bluetoothStream = null
                        if (activeSession != null || wirelessActiveReported.get() || isStaleWirelessRun(generation)) {
                            break
                        }
                        if (attempt < maxAttempts) {
                            try {
                                Thread.sleep(1000L)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                }

                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack(generation = generation)
                    return
                }

                if (controlResult != null) {
                    val result = controlResult
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                            debugLog(
                                "wireless RFCOMM EOF: iap2State=${result.stage} " +
                                    "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                                    "transportIdentifier=${result.transportNotificationSeen} " +
                                    "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                                    "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                                    "handoffRequested=${wirelessHandoffRequested.get()} " +
                                    "tunnelReady=${wirelessTunnelReady.get()} " +
                                    "wirelessActive=${wirelessActiveReported.get()}",
                            )
                            if (!wirelessActiveReported.get()) {
                                val handoffInProgress = isWirelessHandoffInProgress(
                                    handoffRequested = wirelessHandoffRequested.get(),
                                    tunnelActive = wirelessTunnelChannel != null,
                                    sessionActive = activeSession != null,
                                    startSessionSent = result.carPlayStartSessionsSent > 0,
                                )
                                if (!handoffInProgress) {
                                    throw IOException(
                                        "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                                    )
                                }
                                if (result.carPlayStartSessionsSent > 0 && !wirelessHandoffRequested.get()) {
                                    armWirelessHandoffWatchdog(generation)
                                }
                                debugLog(
                                    "wireless Bluetooth bootstrap closed during handoff; " +
                                        "keeping the Wi-Fi AirPlay tunnel alive",
                                )
                            }
                        }
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            if (!wirelessActiveReported.get()) {
                                throw IOException("Wireless iAP2 bootstrap timeout after ${WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS}ms " +
                                    "stage=${result.stage} startSessions=${result.carPlayStartSessionsSent} " +
                                    "wifiConfigs=${result.wifiConfigurationsSent} " +
                                    "transportNotified=${result.transportNotificationSeen}; " +
                                    "check iPhone Bluetooth pairing, Wi-Fi and diagnostic log")
                            }
                    }
                } else {
                    debugLog("wireless Bluetooth bootstrap failed or unavailable (${lastBootstrapError?.message}); falling back to Wi-Fi discovery")
                    waitForWifiDiscovery(generation)
                    if (isStaleWirelessRun(generation)) {
                        closeWirelessStack(generation = generation)
                        return
                    }
                    if (activeSession != null || wirelessActiveReported.get()) {
                        debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                        onStatus(CarPlayStatus.WirelessActive)
                        return
                    }
                    throw (lastBootstrapError ?: IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot"))
                }
            } else {
                debugLog("wireless Bluetooth bootstrap unavailable; waiting for AirPlay connection over Wi-Fi")
                waitForWifiDiscovery(generation)
                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack(generation = generation)
                    return
                }
                if (activeSession != null || wirelessActiveReported.get()) {
                    debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                    onStatus(CarPlayStatus.WirelessActive)
                    return
                }
                throw IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot")
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                fail(error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean {
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiSession?.client ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        wirelessTunnelChannel = channel
        val generation = wirelessGeneration.get()
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) wirelessDiagnostics?.connectionAccepted()
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || !watchdog.sessionEstablished()) return
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                // Keep wirelessActiveReported false until maybeCompleteWirelessHandoff() closes
                // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun onWirelessTunnelReady(generation: Int) {
        synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                wirelessFailureReported.get()) return
            wirelessTunnelReady.set(true)
        }
        wirelessConnectionProof.authenticated(generation)
        debugLog("wireless iAP2 tunnel ready; handoffRequested=${wirelessHandoffRequested.get()}")
        maybeCompleteWirelessHandoff()
    }

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    private fun maybeCompleteWirelessHandoff() {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || wirelessFailureReported.get() ||
                !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    // A queued completion must not close a replacement generation's bootstrap.
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                        wirelessFailureReported.get() || !wirelessTunnelReady.get()) return@Thread
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    closeBluetoothBootstrapTransport()
                    onStatus(CarPlayStatus.WirelessActive, generation)
                }
            },
            "xcertplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun keepBluetoothControlAlive(generation: Int): Boolean = synchronized(wirelessResourceLock) {
        !closed && phase == Phase.WIRELESS && generation == wirelessGeneration.get() &&
            !wirelessFailureReported.get() && activeSession != null &&
            wirelessConnectionProof.hasRenderedFrame(generation)
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    { handleWirelessHandoffTimeout(generation) },
                    "xcertplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        // Session establishment alone also occurs on black-screen failures. Only a rendered
        // frame proves that projection is usable when the optional tunneled control is absent.
        if (activeSession != null && wirelessConnectionProof.hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            wirelessHandoffFellBack.set(true)
            // The Bluetooth link is the only iAP2 channel here. Closing it would stop NowPlaying,
            // route guidance and call updates for the rest of the session, so keep it until the
            // tunnel really takes over (see maybeCompleteWirelessHandoff).
            debugLog("wireless handoff fallback after rendered video; tunnel iAP2 unavailable; " +
                "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control")
            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
        } else {
            closeWirelessStack(generation = generation)
            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
        }
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }


    private fun scheduleAvailabilityPoll(phase: Phase, attempt: Int = 0, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        // First misses use a short interval so a freshly attached iPhone is seen quickly;
        // later polls back off to the steady 2s cadence to avoid USB binder spam.
        val delayMillis = when {
            attempt < 5 -> DEVICE_AVAILABILITY_FAST_POLL_MILLIS
            attempt < 10 -> DEVICE_AVAILABILITY_MID_POLL_MILLIS
            else -> DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS
        }
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            delayMillis,
        )
    }



    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(generation: Int): WirelessHotspotInfo {
        if (config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P && Build.VERSION.SDK_INT < 28) {
            return startLegacyWifiDirectWithFallback(generation)
        }
        val hotspotMode = config.wirelessHotspotMode
        if (com.shihab.diplay.network.CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            // SoftAP enable budget is separate from interface readiness (see below).
            val result = com.shihab.diplay.network.CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !com.shihab.diplay.network.CarHotspotSettings.enabled(appContext) },
                timeoutMillis = WirelessStartupPolicy.SOFTAP_ENABLE_MILLIS,
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting SoftAP enable") },
            )
            val manualFallback = result == com.shihab.diplay.network.CarHotspotTethering.Result.UNSUPPORTED &&
                com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext) == null
            if (result != com.shihab.diplay.network.CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == com.shihab.diplay.network.CarHotspotTethering.Result.TIMED_OUT ||
                        result == com.shihab.diplay.network.CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (hotspotMode == WirelessHotspotMode.MANUAL &&
            com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext) == false
        ) {
            // MANUAL wireless on KitKat SoftAP: always try hidden setWifiApEnabled when the
            // car hotspot is off. Independent of the ADB-gated auto-enable setting.
            val requested = com.shihab.diplay.network.CarHotspotStatus.enableIfPossible(appContext)
            if (requested != true) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "The car hotspot is off. Turn it on in the car settings and connect again.")
            }
            // Launch warmup or a racing enable may already have flipped SoftAP on.
            if (com.shihab.diplay.network.CarHotspotStatus.isEnabled(appContext) == true) {
                debugLog("car hotspot enable requested; SoftAP already ENABLED")
            } else {
                debugLog("car hotspot was off; enable requested; waiting for SoftAP ENABLED")
                CarHotspotEnableGuard.awaitSoftApEnabled(appContext) { isStaleWirelessRun(generation) }
            }
        }
        val manager: WirelessHotspotManager = HotspotPolicyRouter.create(
            mode = hotspotMode,
            context = appContext,
            config = config,
            onLog = ::debugLog,
            onManualDiagnostic = { debugLog("generation=$generation $it") },
            isCancelled = { isStaleWirelessRun(generation) },
            onNetworkChanged = { if (!isStaleWirelessRun(generation)) restartWireless() },
        )
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            hotspot = manager
        }
        // Fresh interface-ready budget after SoftAP is on (not shared with enable wait).
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            WirelessStartupPolicy.INTERFACE_READY_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    /**
     * Wait for an AirPlay session when Bluetooth bootstrap is unavailable.
     * Periodically re-checks SoftAP so a dropped hotspot fails fast instead of sitting out the full budget.
     */
    private fun waitForWifiDiscovery(generation: Int) {
        val deadline = System.currentTimeMillis() + WirelessStartupPolicy.WIFI_DISCOVERY_WAIT_MILLIS
        var nextValidateAt = 0L
        while (
            activeSession == null &&
            !wirelessActiveReported.get() &&
            System.currentTimeMillis() < deadline &&
            !isStaleWirelessRun(generation)
        ) {
            val now = System.currentTimeMillis()
            if (now >= nextValidateAt) {
                nextValidateAt = now + 5_000L
                runCatching { hotspot?.validateReady() }.onFailure { error ->
                    debugLog("Wi-Fi discovery aborted; hotspot no longer ready: ${error.message}")
                    throw error
                }
            }
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun startLegacyWifiDirectWithFallback(generation: Int): WirelessHotspotInfo {
        var primary: WirelessHotspotManager? = null
        try {
            primary = LegacyWifiP2pGroupManager(appContext, ::debugLog)
            hotspot = primary
            debugLog("wireless legacy Wi-Fi Direct starting")
            return primary.start(LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS)
        } catch (primaryFailure: Exception) {
            if (hotspot === primary) hotspot = null
            closeBestEffort("legacy-wifi-p2p") { primary?.close() }
            if (isStaleWirelessRun(generation)) throw primaryFailure
            if (Build.VERSION.SDK_INT < 26) throw IOException(
                "Wi-Fi Direct could not start on Android 7; use the car hotspot: ${primaryFailure.message}", primaryFailure,
            )
            debugLog(
                "legacy Wi-Fi Direct failed; falling back to LocalOnlyHotspot: " +
                    (primaryFailure.message ?: primaryFailure.javaClass.simpleName),
            )

            val fallback = LocalOnlyHotspotManager(appContext, ::debugLog)
            hotspot = fallback
            try {
                return fallback.start(HOTSPOT_START_TIMEOUT_MILLIS)
            } catch (fallbackFailure: Exception) {
                if (hotspot === fallback) hotspot = null
                closeBestEffort("legacy-local-hotspot-fallback") { fallback.close() }
                if (isStaleWirelessRun(generation)) throw fallbackFailure
                fallbackFailure.addSuppressed(primaryFailure)
                throw IOException(
                    "Wi-Fi Direct failed (${primaryFailure.message ?: primaryFailure.javaClass.simpleName}); " +
                        "local hotspot fallback also failed " +
                        "(${fallbackFailure.message ?: fallbackFailure.javaClass.simpleName})",
                    fallbackFailure,
                )
            }
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(this) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.let { return it }
            if (BluetoothAdapter.checkBluetoothAddress(selected)) {
                runCatching { adapter.getRemoteDevice(selected) }.getOrNull()?.let { return it }
            }
            debugLog("saved wireless iPhone is no longer paired; falling back to current bonded devices")
        }
        val iPhones = bonded.filter { device ->
            device.name?.contains("iPhone", ignoreCase = true) == true
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw IOException(
                "Multiple connected iPhones found: " +
                    connectedIPhones.joinToString { "${it.name ?: "iPhone"} (${it.address})" },
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw IOException(
                "Multiple bonded iPhones found and none is currently connected; " +
                    "connect one iPhone and retry",
            )
        }
        val hybridBt = CarPlayBluetoothManager.get(appContext)
        val connectedDevice = hybridBt.getConnectedDevice()
        if (connectedDevice != null && BluetoothAdapter.checkBluetoothAddress(connectedDevice.address)) {
            Log.i(
                TAG,
                "Using connected device from ${hybridBt.getAdapterName()}: " +
                    "${connectedDevice.name} (${connectedDevice.address})",
            )
            return adapter.getRemoteDevice(connectedDevice.address)
        }
        val pairedDevices = hybridBt.getPairedDevices()
        val pairedIPhones = pairedDevices.filter { it.name.contains("iPhone", ignoreCase = true) }
        if (pairedIPhones.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedIPhones.single().address)) {
            Log.i(
                TAG,
                "Using single paired iPhone from ${hybridBt.getAdapterName()}: " +
                    "${pairedIPhones.single().name} (${pairedIPhones.single().address})",
            )
            return adapter.getRemoteDevice(pairedIPhones.single().address)
        }
        if (pairedDevices.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedDevices.single().address)) {
            Log.i(
                TAG,
                "Using single paired device from ${hybridBt.getAdapterName()}: " +
                    "${pairedDevices.single().name} (${pairedDevices.single().address})",
            )
            return adapter.getRemoteDevice(pairedDevices.single().address)
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous bonded iPhone found; pair one iPhone and retry",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        runCatching { bluetoothAdapter?.cancelDiscovery() }
            .onFailure { debugLog("wireless Bluetooth discovery cancel failed: ${it.javaClass.simpleName}") }
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(rfcommConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            val timeoutMillis = rfcommConnectTimeoutMillis()
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${timeoutMillis}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${timeoutMillis}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            val bondState = device.bondState
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayWirelessService? = vpnService, generation: Int? = null) =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            val diagnostics = wirelessDiagnostics
            wirelessDiagnostics = null
            diagnostics?.close()
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            val activeTunnel = wirelessTunnelChannel
            wirelessTunnelChannel = null
            if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

            closeBluetoothBootstrapTransport()

            val activeBonjour = bonjour
            bonjour = null
            if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

            val activeHotspot = hotspot
            hotspot = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)

            if (service != null && owner != null) closeBestEffort("AirPlay service") { service.detachWireless(owner) }
        }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter?): String {
        val managerAddress = try {
            CarPlayBluetoothManager.get(appContext).localAddress(appContext)
        } catch (_: Throwable) {
            null
        }
        val address = managerAddress ?: BluetoothAddressResolver.resolve(appContext)
        return address ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Wireless hotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }


    private fun awaitVpnService(): CarPlayWirelessService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        // A failed earlier bind may have counted down the previous latch. Reset it
        // before each new bind or a reconnect can observe a stale completed latch.
        vpnLatch = CountDownLatch(1)
        vpnBound = true
        try {
            val intent = Intent(appContext, CarPlayWirelessService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: Exception) {
            // The service may have already been unbound.
        }
        vpnService = null
    }

    private fun closeReceivers() {
        // USB permission/attach receivers were removed along with the wired transport.
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val causes = generateSequence(error) { it.cause }.toList()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                wifiResetRequired = false,
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason),
                if (wireless) wirelessGeneration.get() else null)
        }
    }

    private fun debugLog(message: String) {
        Log.i(TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(TAG, message, error)
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val TAG = "xcertplay"
        private const val ENABLE_PHONE_DIAGNOSTIC_CAPTURE = false
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS = 25_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS = 90_000L
        private const val DEVICE_AVAILABILITY_FAST_POLL_MILLIS = 400L
        private const val DEVICE_AVAILABILITY_MID_POLL_MILLIS = 800L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        /** Matches [AndroidBluetoothAdapter] KitKat RFCOMM budget so the waiter does not cut short. */
        private const val RFCOMM_CONNECT_TIMEOUT_LEGACY_MILLIS = 18_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }

    private fun rfcommConnectTimeoutMillis(): Long = RFCOMM_CONNECT_TIMEOUT_LEGACY_MILLIS
}
