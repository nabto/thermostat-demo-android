package com.nabto.edge.tunnelvideodemo

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.widget.addTextChangedListener
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.nabto.edge.client.ErrorCodes
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.TcpTunnel
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUser
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitGetCurrentUser
import com.nabto.edge.sharedcode.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.koin.android.ext.android.inject

class DevicePageViewModelFactory(
    private val productId: String,
    private val deviceId: String,
    private val database: DeviceDatabase,
    private val connectionManager: NabtoConnectionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            String::class.java,
            String::class.java,
            DeviceDatabase::class.java,
            NabtoConnectionManager::class.java
        ).newInstance(productId, deviceId, database, connectionManager)
    }
}

/**
 * Represents different states that the [DevicePageFragment] can be in
 * [INITIAL_CONNECTING] is used for when the user moves from [HomeFragment] to [DevicePageFragment]
 * where we initially display a loading spinner while a connection is being made.
 */
enum class AppConnectionState {
    INITIAL_CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * Represents different events that might happen while the user is interacting with the device
 */
enum class AppConnectionEvent {
    RECONNECTED,
    FAILED_RECONNECT,
    FAILED_INITIAL_CONNECT,
    FAILED_INCORRECT_APP,
    FAILED_UNKNOWN,
    FAILED_NOT_PAIRED,
    FAILED_NO_SUCH_SERVICE
}

/**
 * [ViewModel] that manages data for [DevicePageFragment].
 * This class is responsible for interacting with [NabtoConnectionManager] to do
 * COAP calls for getting or setting device state as well as requesting and releasing
 * connection handles from the manager.
 */
class DevicePageViewModel(
    private val productId: String,
    private val deviceId: String,
    private val database: DeviceDatabase,
    private val connectionManager: NabtoConnectionManager
) : ViewModel() {
    private val TAG = this.javaClass.simpleName

    // COAP paths that the ViewModel will use for getting/setting data
    private val _connState: MutableLiveData<AppConnectionState> = MutableLiveData(AppConnectionState.INITIAL_CONNECTING)
    private val _connEvent: MutableLiveEvent<AppConnectionEvent> = MutableLiveEvent()
    private val _currentUser: MutableLiveData<IamUser> = MutableLiveData()
    private val _device = MutableLiveData<Device>()

    private val listener = ConnectionEventListener { event, _ -> onConnectionChanged(event) }

    private var isPaused = false
    private var startupJob: Job? = null
    private lateinit var handle: ConnectionHandle

    private var tunnel: TcpTunnel? = null
    private val _tunnelPort = MutableLiveData<Int>()
    val tunnelPort: LiveData<Int>
        get() = _tunnelPort

    data class RTSPURL(val port: Int, val path: String, val auth: String) {
        override fun toString(): String {
            return "rtsp://${auth}127.0.0.1:$port$path"
        }
    }

    private val _rtspUrl = MutableLiveData<RTSPURL>()
    val rtspUrl: LiveData<RTSPURL>
        get() = _rtspUrl

    var customEndpoint = ""

    /**
     * connectionState contains the current state of the connection that the DevicePageFragment
     * uses for updating UI.
     */
    val connectionState: LiveData<AppConnectionState>
        get() = _connState.distinctUntilChanged()

    /**
     * connectionEvent sends out an event when something happens to the connection.
     * DevicePageFragment uses this to act correspondingly with the event in cases of
     * connection being lost or other such error states.
     */
    val connectionEvent: LiveEvent<AppConnectionEvent>
        get() = _connEvent

    /**
     * The user info of the currently connected user. DevicePageFragment can use this to
     * represent information about the user in the UI.
     */
    val currentUser: LiveData<IamUser>
        get() = _currentUser

    val device: LiveData<Device>
        get() = _device

    init {
        viewModelScope.launch {
            val dao = database.deviceDao()
            val dev = withContext(Dispatchers.IO) { dao.get(productId, deviceId) }
            _device.postValue(dev)
            handle = connectionManager.requestConnection(dev)
            connectionManager.subscribe(handle, listener)
            if (connectionManager.getConnectionState(handle)?.value == NabtoConnectionState.CONNECTED) {
                // We're already connected from the home page.
                startup()
            }

            // we may have been handed a closed connection from the home page
            // try to reconnect it if that is the case.
            if (connectionManager.getConnectionState(handle)?.value == NabtoConnectionState.CLOSED) {
                connectionManager.connect(handle)
            }
        }
    }

    private fun startup() {
        isPaused = false

        startupJob = viewModelScope.launch {
            val iam = IamUtil.create()

            try {
                val isPaired =
                    iam.isCurrentUserPaired(connectionManager.getConnection(handle))
                if (!isPaired) {
                    Log.i(TAG, "User connected to device but is not paired!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_NOT_PAIRED)
                    return@launch
                }

                val details = iam.getDeviceDetails(connectionManager.getConnection(handle))
                if (details.appName != NabtoConfig.DEVICE_APP_NAME) {
                    Log.i(TAG, "The app name of the connected device is ${details.appName} instead of ${NabtoConfig.DEVICE_APP_NAME}!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_INCORRECT_APP)
                    return@launch
                }

                if (_connState.value == AppConnectionState.DISCONNECTED) {
                    _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
                }

                startTunnelService()

                val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                _currentUser.postValue(user)
            } catch (e: IamException) {
                Log.w(TAG, "Startub job received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: NabtoRuntimeException) {
                Log.w(TAG, "Startup job received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: CancellationException) {
                Log.w(TAG, "Startup job received CancellationException: ${e.message}")
            }
        }
    }

    private fun onConnectionChanged(state: NabtoConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            NabtoConnectionEvent.CONNECTED -> {
                startup()
            }

            NabtoConnectionEvent.DEVICE_DISCONNECTED -> {
                startupJob?.cancel()
                startupJob = null
                _connState.postValue(AppConnectionState.DISCONNECTED)
            }

            NabtoConnectionEvent.FAILED_TO_CONNECT -> {
                if (_connState.value == AppConnectionState.INITIAL_CONNECTING) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_INITIAL_CONNECT)
                } else {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_RECONNECT)
                }
                _connState.postValue(AppConnectionState.DISCONNECTED)
            }

            NabtoConnectionEvent.CLOSED -> {
                startupJob?.cancel()
                startupJob = null
                _connState.postValue(AppConnectionState.DISCONNECTED)
            }

            NabtoConnectionEvent.PAUSED -> {
                isPaused = true
            }

            NabtoConnectionEvent.UNPAUSED -> {
                viewModelScope.launch {
                    startTunnelService()
                }
            }
            else -> {}
        }
    }

    /**
     * Try to reconnect a disconnected connection.
     *
     * If the connection is not disconnected, a RECONNECTED event will be sent out.
     */
    fun tryReconnect() {
        if (_connState.value == AppConnectionState.DISCONNECTED) {
            connectionManager.connect(handle)
        } else {
            _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
        }
    }

    suspend fun startTunnelService() {
        Log.i(TAG, "Attempting to open tunnel service...")
        if (connectionManager.getConnectionState(handle)?.value == NabtoConnectionState.CONNECTED) {
            tunnel?.close()
            tunnel = try {
                connectionManager.openTunnelService(handle, "rtsp")
            } catch (e: NabtoRuntimeException) {
                if (e.errorCode.errorCode == ErrorCodes.NOT_FOUND) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_NO_SUCH_SERVICE)
                } else {
                    throw e
                }
                null
            }

            tunnel?.let {
                _connState.postValue(AppConnectionState.CONNECTED)
                _tunnelPort.postValue(it.localPort)

                val url = run {
                    val coap =
                        connectionManager.createCoap(handle, "GET", "/tcp-tunnels/services/rtsp")
                    coap.execute()
                    val res = coap.responseStatusCode
                    Log.i(TAG, "Coap got $res response")

                    @Serializable
                    data class ServiceInfo(
                        @Required @SerialName("Id") val serviceId: String,
                        @Required @SerialName("Type") val type: String,
                        @Required @SerialName("Host") val host: String,
                        @Required @SerialName("Port") val port: Int,
                        @Required @SerialName("StreamPort") val streamPort: Int,
                        @Required @SerialName("Metadata") val metadata: Map<String, String>
                    )

                    val payload = Cbor.decodeFromByteArray<ServiceInfo>(coap.responsePayload)

                    val ep = customEndpoint.ifEmpty { payload.metadata.getOrDefault("rtsp-path", NabtoConfig.RTSP_ENDPOINT) }
                    val username = payload.metadata.getOrDefault("rtsp-username", "")
                    val password = payload.metadata.getOrDefault("rtsp-password", "")
                    val auth = if (username.isEmpty()) "" else "${username}:${password}@"

                    RTSPURL(it.localPort, ep, auth)
                }

                _rtspUrl.postValue(url)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
        connectionManager.unsubscribe(handle, listener)
    }
}

/**
 * Fragment for fragment_device_page.xml.
 * Responsible for letting the user interact with their thermostat device.
 *
 * [DevicePageFragment] sets observers on [LiveData] received from [DevicePageViewModel]
 * and receives [AppConnectionEvent] updates.
 */
class DevicePageFragment : Fragment(), MenuProvider {
    private val TAG = this.javaClass.simpleName

    private val model: DevicePageViewModel by navGraphViewModels(R.id.nav_device) {
        val productId = arguments?.getString("productId") ?: ""
        val deviceId = arguments?.getString("deviceId") ?: ""
        val connectionManager: NabtoConnectionManager by inject()
        val database: DeviceDatabase by inject()
        DevicePageViewModelFactory(
            productId,
            deviceId,
            database,
            connectionManager
        )
    }

    private lateinit var mainLayout: View
    private lateinit var lostConnectionBar: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingSpinner: View
    private lateinit var etPath: EditText

    private lateinit var videoPlayer: StyledPlayerView
    private lateinit var exoPlayer: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exoPlayer = ExoPlayer.Builder(requireActivity()).apply {
            val loadControl = DefaultLoadControl.Builder().apply {
                setBufferDurationsMs(1000, 2000, 1000, 1000)
            }.build()
            setLoadControl(loadControl)
        }.build()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        mainLayout = view.findViewById(R.id.dp_main)
        swipeRefreshLayout = view.findViewById(R.id.dp_swiperefresh)
        lostConnectionBar = view.findViewById(R.id.dp_lost_connection_bar)
        loadingSpinner =  view.findViewById(R.id.dp_loading)
        videoPlayer = view.findViewById(R.id.video_player)
        etPath = view.findViewById(R.id.et_rtsp_path)

        videoPlayer.player = exoPlayer

        model.connectionState.observe(viewLifecycleOwner, Observer { state -> onConnectionStateChanged(view, state) })
        model.connectionEvent.observe(viewLifecycleOwner) { event -> onConnectionEvent(view, event) }

        swipeRefreshLayout.setOnRefreshListener {
            refresh()
        }

        model.currentUser.observe(viewLifecycleOwner, Observer {
            view.findViewById<TextView>(R.id.dp_info_userid).text = it.username
        })

        model.device.observe(viewLifecycleOwner, Observer { device ->
            view.findViewById<TextView>(R.id.dp_info_appname).text = device.appName
            view.findViewById<TextView>(R.id.dp_info_devid).text = device.deviceId
            view.findViewById<TextView>(R.id.dp_info_proid).text = device.productId
            requireAppActivity().actionBarTitle = device.friendlyName
        })

        model.rtspUrl.observe(viewLifecycleOwner, Observer { rtspUrl ->
            view.findViewById<TextView>(R.id.dp_info_url).text = rtspUrl.toString()
            etPath.setText(rtspUrl.path)
            exoPlayer.apply {
                Log.i(TAG, "Using url $rtspUrl")
                val item = MediaItem.Builder().apply {
                    setUri(rtspUrl.toString())
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().apply {
                            setTargetOffsetMs(300)
                            setMaxPlaybackSpeed(1.04f)
                        }.build()
                    )
                }.build()
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(item)
                setMediaSource(source)
                playWhenReady = true
                prepare()
                this.isCurrentMediaItemLive
            }
        })

        videoPlayer.useController = false

        view.findViewById<Button>(R.id.dp_refresh_video).setOnClickListener {
            lifecycleScope.launch {
                model.customEndpoint = etPath.text.toString()
                model.startTunnelService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        exoPlayer.playWhenReady = true
    }

    private fun refresh() {
        model.tryReconnect()
    }

    private fun onConnectionStateChanged(view: View, state: AppConnectionState) {
        Log.i(TAG, "Connection state changed to $state")

        when (state) {
            AppConnectionState.INITIAL_CONNECTING -> {
                swipeRefreshLayout.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
            }

            AppConnectionState.CONNECTED -> {
                loadingSpinner.visibility = View.INVISIBLE
                lostConnectionBar.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
            }

            AppConnectionState.DISCONNECTED -> {
                lostConnectionBar.visibility = View.VISIBLE
                exoPlayer.stop()
            }
        }
    }

    private fun onConnectionEvent(view: View, event: AppConnectionEvent) {
        Log.i(TAG, "Received connection event $event")
        when (event) {
            AppConnectionEvent.RECONNECTED -> {
                swipeRefreshLayout.isRefreshing = false
            }

            AppConnectionEvent.FAILED_RECONNECT -> {
                swipeRefreshLayout.isRefreshing = false
                view.snack(getString(R.string.failed_reconnect))
            }

            AppConnectionEvent.FAILED_INITIAL_CONNECT -> {
                view.snack(getString(R.string.device_page_failed_to_connect))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_INCORRECT_APP -> {
                view.snack(getString(R.string.device_page_incorrect_app))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_NOT_PAIRED -> {
                view.snack(getString(R.string.device_failed_not_paired))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_NO_SUCH_SERVICE -> {
                view.snack(getString(R.string.device_failed_no_such_service))
            }

            AppConnectionEvent.FAILED_UNKNOWN -> { }
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_device, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_refresh) {
            swipeRefreshLayout.isRefreshing = true
            refresh()
            return true
        }
        return false
    }
}
