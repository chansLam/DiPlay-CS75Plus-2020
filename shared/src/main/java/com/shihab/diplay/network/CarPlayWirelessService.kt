package com.shihab.diplay.network

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.airplay.AirPlaySocketBinder
import com.shihab.diplay.airplay.AirPlayTcpAccepted
import com.shihab.diplay.airplay.isInternalAirPlayPeer
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.mfi.MfiAuthenticator
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener on the wireless SoftAP address.
 *
 * The head unit has no USB host path and no /dev/tun: the iPhone connects over the wireless
 * hotspot and the listener binds directly on the AP address. This is a plain [Service] that
 * never establishes a VPN/tun interface. VPN consent is intentionally skipped because the
 * stripped ROM has neither tun support nor the vpndialogs consent dialog.
 */
class CarPlayWirelessService : Service() {
    inner class LocalBinder : Binder() {
        val service: CarPlayWirelessService get() = this@CarPlayWirelessService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var attachGeneration = 0
    @Volatile var lastAttachFailure: String? = null
        private set

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val scopedBind = AirPlaySocketBinder.scopedLinkLocal(bindAddress)
            val scopedAdditional = additionalBindAddresses.map { AirPlaySocketBinder.scopedLinkLocal(it) }
            startAirPlayServer(
                generation,
                AirPlayAttachment(scopedBind, config, identity, pairings, mfi, listener, media,
                    scopedAdditional, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
    }

    companion object {
        private const val TAG = "xcertplay-wireless"
    }
}
