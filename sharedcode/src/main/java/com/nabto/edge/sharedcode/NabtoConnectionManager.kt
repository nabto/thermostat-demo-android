package com.nabto.edge.sharedcode

import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.*
import com.nabto.edge.client.*
import com.nabto.edge.client.ktx.awaitConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class NabtoConnectionState {
    CLOSED,
    CONNECTING,
    CONNECTED
}

enum class NabtoConnectionEvent {
    /** the device is in the process of being connected to */
    CONNECTING,

    /** the device has been connected to, always comes after [CONNECTING] */
    CONNECTED,

    /** the device has been disconnected for some reason */
    DEVICE_DISCONNECTED,

    /** failed to connect to the device, always comes after [CONNECTING] */
    FAILED_TO_CONNECT,

    FAILED_TO_CONNECT_NO_CHANNELS,

    /**
     * the connection is paused, it is still connected but the connection may close soon
     * serves as a warning for subscribers to stop using the connection
     */
    PAUSED,

    /**
     * the connection has gone from being paused to unpaused
     * which means it will not close down and may still be used
     * always comes after [PAUSED]
     */
    UNPAUSED,

    /**
     * the connection is closed and should not be used anymore
     */
    CLOSED
}

data class ConnectionHandle(
    val productId: String,
    val deviceId: String
)

fun interface ConnectionEventListener {
    fun onConnectionEvent(event: NabtoConnectionEvent, handle: ConnectionHandle)
}

/**
 * Manager whose responsibility is keeping track of connections to Nabto Edge devices.
 * This is the most important interface with its corresponding class [NabtoConnectionManagerImpl]
 * Can be injected using Koin
 */
interface NabtoConnectionManager {
    /**
     * Request a connection to a device. Will return a [ConnectionHandle] and will attempt
     * to connect to the device, but it is not guaranteed that connection will succeed.
     * Use a [ConnectionEventListener] to wait for [NabtoConnectionEvent.CONNECTED], if
     * connection fails then the listener will get [NabtoConnectionEvent.FAILED_TO_CONNECT]
     *
     * Use [releaseHandle] to close the connection and invalidate the [ConnectionHandle]
     *
     * @param[device] The [Device] to which a connection should be established
     * @param[listener] A [ConnectionEventListener] that will receive [NabtoConnectionEvent] events.
     * @return A [ConnectionHandle] that represents a connection to [device]
     */
    fun requestConnection(device: Device): ConnectionHandle

    /**
     * Reconnected a closed connection. If the connection is already connected, this function
     * does not do anything.
     *
     * @param[handle] The [ConnectionHandle] to reconnect
     */
    fun connect(handle: ConnectionHandle)

    /**
     * Release a [ConnectionHandle]. The underlying connection is closed, subscribers are
     * unsubscribed and the handle is invalidated.
     *
     * @param[handle] The [ConnectionHandle] to be released
     */
    fun releaseHandle(handle: ConnectionHandle)

    /**
     * Subscribes to [NabtoConnectionEvent] events for the given [handle], the events are delivered to
     * [listener]
     *
     * @param[handle] The [ConnectionHandle] to subscribe for events to
     * @param[listener] A listener that will receive connection events from [handle]
     */
    fun subscribe(handle: ConnectionHandle, listener: ConnectionEventListener)

    /**
     * Unsubscribes [listener] so that it will not receive any more [NabtoConnectionEvent] events
     * from [handle]
     *
     * @param[handle] The handle to unsubscribe from
     * @param[listener] the listener that was subscribed
     */
    fun unsubscribe(handle: ConnectionHandle, listener: ConnectionEventListener)

    /**
     * Get a raw [Connection] object from the Nabto SDK.
     * You should only call this when you actually need the underlying [Connection]
     * and you should not save the reference.
     *
     * @param[handle] The handle that represents the desired [Connection]
     * @return A [Connection] object for [handle]
     *
     * @throws[IllegalStateException] If [handle] is not valid.
     */
    fun getConnection(handle: ConnectionHandle): Connection

    /**
     * Get a [Coap] object for the underlying connection which can be used to make coap requests.
     *
     * @param[handle] Handle for a connection.
     * @param[method] The coap method for the request (e.g. GET or POST).
     * @param[path] The coap path for the request.
     * @return A [Coap] object with the specified method and path.
     *
     * @throws[IllegalStateException] If [handle] is not valid.
     */
    fun createCoap(handle: ConnectionHandle, method: String, path: String): Coap

    /**
     * Get a [LiveData] object holding a [NabtoConnectionState] representing the connection
     * state of [handle].
     *
     * @param[handle] A [ConnectionHandle].
     * @return A [LiveData] object holding a [NabtoConnectionState] object, or null if
     * [handle] is invalid.
     */
    fun getConnectionState(handle: ConnectionHandle?): LiveData<NabtoConnectionState>?

    // @TODO: Documentation
    suspend fun openTunnelService(handle: ConnectionHandle, service: String): TcpTunnel

    fun releaseAll()
}

class NabtoConnectionManagerImpl(
    private val repo: NabtoRepository,
    private val client: NabtoClient,
    private val connectivityManager: ConnectivityManager
): NabtoConnectionManager, LifecycleEventObserver {
    @Serializable
    data class ConnectionOptions(
        @Required var ProductId: String,
        @Required var DeviceId: String,
        @Required var PrivateKey: String,
        @Required var ServerConnectToken: String,
        var KeepAliveInterval: Int,
        var KeepAliveRetryInterval: Int,
        var KeepAliveMaxRetries: Int
    )

    data class ConnectionData(
        var connection: Connection?,
        val state: AtomicReference<NabtoConnectionState>,
        val stateLiveData: MutableLiveData<NabtoConnectionState>,
        val connectionEventsCallback: ConnectionEventsCallback,
        var options: ConnectionOptions,
        val subscribers: MutableList<ConnectionEventListener> = mutableListOf()
    )

    private val TAG = "NabtoConnectionManager"
    private val connectionMap = ConcurrentHashMap<ConnectionHandle, ConnectionData>()
    private var activeNetwork = connectivityManager.activeNetwork
    private var isAppInBackground = false

    private val singleDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = repo.getApplicationScope()

    // If the app goes into the background, how long do we wait before killing connections?
    private val keepAliveTimeoutSeconds = 20L

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                onNetworkLost(network)
            }
        })
    }

    override fun subscribe(handle: ConnectionHandle, listener: ConnectionEventListener) {
        connectionMap[handle]?.subscribers?.let {
            if (!it.contains(listener)) {
                it.add(listener)
            }
        }
    }

    override fun unsubscribe(handle: ConnectionHandle, listener: ConnectionEventListener) {
        connectionMap[handle]?.subscribers?.remove(listener)
    }

    private fun publish(data: ConnectionData?, event: NabtoConnectionEvent, handle: ConnectionHandle) {
        val state = when (event) {
            NabtoConnectionEvent.CONNECTED -> NabtoConnectionState.CONNECTED
            NabtoConnectionEvent.CONNECTING -> NabtoConnectionState.CONNECTING

            NabtoConnectionEvent.DEVICE_DISCONNECTED,
            NabtoConnectionEvent.FAILED_TO_CONNECT_NO_CHANNELS,
            NabtoConnectionEvent.FAILED_TO_CONNECT,
            NabtoConnectionEvent.CLOSED -> NabtoConnectionState.CLOSED

            NabtoConnectionEvent.PAUSED,
            NabtoConnectionEvent.UNPAUSED -> NabtoConnectionState.CONNECTED
        }
        data?.stateLiveData?.postValue(state)
        data?.state?.set(state)

        data?.subscribers?.forEach {
            it.onConnectionEvent(event, handle)
        }
    }

    private fun publish(handle: ConnectionHandle, event: NabtoConnectionEvent) {
        publish(connectionMap[handle], event, handle)
    }

    override fun connect(handle: ConnectionHandle) {
        connectionMap[handle]?.let { data ->
            if (data.state.get() != NabtoConnectionState.CLOSED) {
                // no-op if we're already connected
                return
            }
            publish(handle, NabtoConnectionEvent.CONNECTING)

            data.connection = client.createConnection()
            data.connection?.let { conn ->
                val opts = Json.encodeToString(data.options)
                conn.updateOptions(opts)
                conn.addConnectionEventsListener(data.connectionEventsCallback)

                scope.launch(singleDispatcher) {
                    try {
                        conn.awaitConnect()
                    } catch (e: Exception) {
                        Log.i(TAG, "Failed to connect, $e")
                        withContext(Dispatchers.Main) {
                            when (e) {
                                is NabtoNoChannelsException -> publish(
                                    handle,
                                    NabtoConnectionEvent.FAILED_TO_CONNECT_NO_CHANNELS
                                )
                                is NabtoRuntimeException -> publish(
                                    handle,
                                    NabtoConnectionEvent.FAILED_TO_CONNECT
                                )
                                else -> throw e
                            }
                        }
                    }

                }
            }

        }
    }

    override fun requestConnection(device: Device): ConnectionHandle {
        val handle = ConnectionHandle(device.productId, device.deviceId)
        if (connectionMap.containsKey(handle)) {
            // there is already an existing connection, just return the handle as-is
            Log.i(TAG, "Requested connection for ${device.deviceId} but a connection already exists")
            connectionMap[handle]?.options?.apply {
                ServerConnectToken = device.SCT
            }
            return handle
        }

        val connectionEventsCallback = object : ConnectionEventsCallback() {
            override fun onEvent(event: Int) {
                when (event) {
                    CLOSED -> {
                        if (connectionMap[handle]?.state?.get() == NabtoConnectionState.CONNECTED) {
                            publish(handle, NabtoConnectionEvent.DEVICE_DISCONNECTED)
                        }
                    }
                    CONNECTED -> {
                        publish(handle, NabtoConnectionEvent.CONNECTED)
                    }
                }
            }
        }

        val options = ConnectionOptions(
            ProductId = device.productId,
            DeviceId = device.deviceId,
            PrivateKey = repo.getClientPrivateKey(),
            ServerConnectToken = device.SCT,
            KeepAliveInterval = 2000,
            KeepAliveRetryInterval = 2000,
            KeepAliveMaxRetries = 5
        )

        // add new connection and subscribe to it
        connectionMap[handle] = ConnectionData(
            null,
            AtomicReference(NabtoConnectionState.CLOSED),
            MutableLiveData(NabtoConnectionState.CLOSED),
            connectionEventsCallback,
            options
        )
        return handle
    }

    // closes the connection but does not release the handle
    private fun close(handle: ConnectionHandle) {
        connectionMap[handle]?.let { data ->
            if (data.state.get() != NabtoConnectionState.CLOSED) {
                publish(handle, NabtoConnectionEvent.CLOSED)
                scope.launch(singleDispatcher) {
                    val conn = data.connection
                    data.connection = null
                    try {
                        conn?.close()
                    } catch (e: NabtoRuntimeException) {
                        if (e.errorCode.errorCode == ErrorCodes.NOT_CONNECTED) {
                            Log.w(TAG, "Tried to close unconnected connection!")
                        } else {
                            throw e
                        }
                    }
                    conn?.removeConnectionEventsListener(data.connectionEventsCallback)
                }
            }
        }
    }

    private fun releaseData(handle: ConnectionHandle, data: ConnectionData) {
        if (data.state.get() != NabtoConnectionState.CLOSED) {
            publish(data, NabtoConnectionEvent.CLOSED, handle)
            scope.launch(singleDispatcher){
                try {
                    data.connection?.removeConnectionEventsListener(data.connectionEventsCallback)
                    data.connection?.close()
                } catch (e: NabtoRuntimeException) {
                    Log.w(TAG, "Attempt to close connection yielded $e")
                }
            }
        }
    }

    override fun releaseHandle(handle: ConnectionHandle) {
        connectionMap.remove(handle)?.let { data ->
            releaseData(handle, data)
        }
    }

    override fun releaseAll() {
        val copy = ConcurrentHashMap(connectionMap)
        connectionMap.clear()
        for ((k, v) in copy) {
            releaseData(k, v)
        }
    }


    override fun getConnection(handle: ConnectionHandle): Connection {
        return connectionMap[handle]?.connection ?: run {
            throw IllegalStateException("Attempted to get Connection for invalid handle!")
        }
    }

    override fun createCoap(handle: ConnectionHandle, method: String, path: String): Coap {
        return connectionMap[handle]?.connection?.createCoap(method, path) ?: run {
            throw IllegalStateException("Attempted to create COAP object for invalid handle!")
        }
    }

    override fun getConnectionState(handle: ConnectionHandle?): LiveData<NabtoConnectionState>? {
        return connectionMap[handle]?.stateLiveData
    }

    override suspend fun openTunnelService(handle: ConnectionHandle, service: String): TcpTunnel {
        return connectionMap[handle]?.let {
            val conn = it.connection ?: run { throw IllegalStateException("Attempted to open a tunnel service on null connection!") }
            val tunnel = conn.createTcpTunnel()
            tunnel.open(service, 0)
            return@let tunnel
        } ?: run {
            throw IllegalStateException("Attempted to open a tunnel service on an invalid handle!")
        }
    }

    private fun onNetworkAvailable(network: Network) {
        if (connectivityManager.activeNetwork == network && activeNetwork != network)
        {
            connectionMap.forEach { (handle, _) -> close(handle) }
        }
        activeNetwork = connectivityManager.activeNetwork

        connectionMap.forEach { (handle, _) ->
            connect(handle)
        }
    }

    private fun onNetworkLost(network: Network) {
        connectionMap.forEach { (handle, _) ->
            close(handle)
        }
        activeNetwork = connectivityManager.activeNetwork
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                isAppInBackground = false
                connectionMap.forEach { (handle, data) ->
                    if (data.state.get() == NabtoConnectionState.CONNECTED) {
                        publish(handle, NabtoConnectionEvent.UNPAUSED)
                    }
                    connect(handle)
                }
            }
            Lifecycle.Event.ON_STOP -> {
                isAppInBackground = true
                connectionMap.forEach { (handle, data) ->
                    if (data.state.get() != NabtoConnectionState.CLOSED) {
                        publish(handle, NabtoConnectionEvent.PAUSED)
                    }

                    // @TODO: We're starting 1 coroutine for each connection, not a good idea.
                    scope.launch {
                        delay(keepAliveTimeoutSeconds * 1000)
                        if (isAppInBackground) {
                            close(handle)
                        }
                    }
                }
            }
            else -> {}
        }
    }
}
