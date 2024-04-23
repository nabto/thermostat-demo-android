package com.nabto.edge.tunnelhttpdemo

import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.nabto.edge.client.ErrorCodes
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.TcpTunnel
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUser
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitGetCurrentUser
import com.nabto.edge.sharedcode.*
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import java.net.URI
import java.net.URL

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
    FAILED_NO_SUCH_SERVICE,
    ALREADY_CONNECTED
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
    private var updateLoopJob: Job? = null
    private lateinit var handle: ConnectionHandle

    private var tunnel: TcpTunnel? = null
    private val _tunnelPort = MutableLiveData<Int>()
    val tunnelPort: LiveData<Int>
        get() = _tunnelPort

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

        updateLoopJob = viewModelScope.launch {
            val iam = IamUtil.create()

            try {
                val isPaired =
                    iam.isCurrentUserPaired(connectionManager.getConnection(handle))
                if (!isPaired) {
                    Log.i(TAG, "User connected to device but is not paired!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_NOT_PAIRED)
                    return@launch
                }

                if (_connState.value == AppConnectionState.DISCONNECTED) {
                    _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
                }

                startTunnelService()

                val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                _currentUser.postValue(user)
            } catch (e: IamException) {
                Log.w(TAG, "Startup job received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: NabtoRuntimeException) {
                Log.w(TAG, "Startup job received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: CancellationException) {
                Log.w(TAG, "Startup job received CancellationException ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
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
                updateLoopJob?.cancel()
                updateLoopJob = null
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
                updateLoopJob?.cancel()
                updateLoopJob = null
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
            _connEvent.postEvent(AppConnectionEvent.ALREADY_CONNECTED)
        }
    }

    private suspend fun startTunnelService() {
        Log.i(TAG, "Attempting to open tunnel service...")
        if (connectionManager.getConnectionState(handle)?.value == NabtoConnectionState.CONNECTED) {
            tunnel?.close()
            tunnel = try {
                connectionManager.openTunnelService(handle, "http")
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

    private lateinit var swipeRefreshLayout: NabtoSwipeRefreshLayout
    private lateinit var loadingSpinner: View
    private lateinit var webView: WebView

    private var device: Device? = null
    private var user: IamUser? = null
    private var tunnelPort = 0

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

        swipeRefreshLayout = view.findViewById(R.id.dp_swiperefresh)
        loadingSpinner =  view.findViewById(R.id.dp_loading)

        webView = view.findViewById(R.id.dp_webview)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                view?.loadUrl(request?.url.toString())
                return false
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                val uri = URI(url)
                requireAppActivity().actionBarSubtitle = uri.path ?: ""
                super.doUpdateVisitedHistory(view, url, isReload)
            }
        }

        requireAppActivity().setNavigationListener(this) {
            if (webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                false
            }
        }

        swipeRefreshLayout.canChildScrollUpCallback = { webView.scrollY > 0 }

        savedInstanceState?.let { webView.restoreState(it) }

        model.connectionState.observe(viewLifecycleOwner, Observer { state -> onConnectionStateChanged(view, state) })
        model.connectionEvent.observe(viewLifecycleOwner) { event -> onConnectionEvent(view, event) }

        swipeRefreshLayout.setOnRefreshListener {
            refresh()
        }

        model.device.observe(viewLifecycleOwner, Observer { device ->
            requireAppActivity().actionBarTitle = device.friendlyName
            this.device = device
        })

        model.currentUser.observe(viewLifecycleOwner, Observer {
            user = it
        })

        model.tunnelPort.observe(viewLifecycleOwner, Observer { port ->
            val urlString = webView.url
            if (urlString == null) {
                webView.loadUrl("http://127.0.0.1:${port}/")
            } else {
                val url = URL(urlString)
                webView.loadUrl("http://127.0.0.1:${port}${url.path}")
            }
            tunnelPort = port
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireAppActivity().actionBarSubtitle = ""
    }

    private fun refresh() {
        model.tryReconnect()
    }

    private fun onConnectionStateChanged(view: View, state: AppConnectionState) {
        Log.i(TAG, "Connection state changed to $state")
        val lostConnectionBar = view.findViewById<View>(R.id.dp_lost_connection_bar)

        when (state) {
            AppConnectionState.INITIAL_CONNECTING -> {
                swipeRefreshLayout.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
            }

            AppConnectionState.CONNECTED -> {
                loadingSpinner.visibility = View.INVISIBLE
                swipeRefreshLayout.visibility = View.VISIBLE
                lostConnectionBar.visibility = View.GONE
            }

            AppConnectionState.DISCONNECTED -> {
                lostConnectionBar.visibility = View.VISIBLE
            }
        }
    }

    private fun onConnectionEvent(view: View, event: AppConnectionEvent) {
        Log.i(TAG, "Received connection event $event")
        when (event) {
            AppConnectionEvent.RECONNECTED -> {
                swipeRefreshLayout.isRefreshing = false
            }

            AppConnectionEvent.ALREADY_CONNECTED -> {
                webView.reload()
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
        if (menuItem.itemId == R.id.action_return_home) {
            findNavController().navigate(AppRoute.home())
            return true
        }
        if (menuItem.itemId == R.id.action_device_refresh) {
            swipeRefreshLayout.isRefreshing = true
            refresh()
            return true
        }
        if (menuItem.itemId == R.id.action_device_information) {
            device?.let { dev ->
                AlertDialog.Builder(requireContext()).apply {
                    setView(R.layout.device_information)
                    setTitle("Device information")
                    setPositiveButton(R.string.ok) { _, _ -> }
                    create().apply {
                        // show must be called __before__ editing views
                        show()

                        findViewById<TextView>(R.id.dp_info_proid)?.text = dev.productId
                        findViewById<TextView>(R.id.dp_info_devid)?.text = dev.deviceId
                        findViewById<TextView>(R.id.dp_info_appname)?.text = dev.appName
                        findViewById<TextView>(R.id.dp_info_userid)?.text = user?.username
                        findViewById<TextView>(R.id.dp_info_tunnelport)?.text = tunnelPort.toString()
                    }
                }
            }
            return true
        }
        return false
    }
}
