package com.nabto.edge.thermostatdemo

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nabto.edge.client.Coap
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.awaitExecute
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUser
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitGetCurrentUser
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.koin.android.ext.android.inject
import kotlin.math.roundToInt
import com.nabto.edge.sharedcode.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/**
 * Enum that represent the states that the thermostat can be in
 */
enum class AppMode(val string: String) {
    COOL("COOL"),
    HEAT("HEAT"),
    FAN("FAN"),
    DRY("DRY")
}

/**
 * AppState is a dumb data class meant to hold the data that is received
 * from a GET procedure to the COAP path that holds the app's state
 */
data class AppState(
    var mode: AppMode,
    var power: Boolean,
    var target: Double,
    var temperature: Double,
)

internal fun decodeAppStateFromCBOR(cbor: ByteArray): AppState {
    @Serializable
    data class AppCoapState(
        @Required @SerialName("Mode") val mode: String,
        @Required @SerialName("Power") val power: Boolean,
        @Required @SerialName("Target") val target: Double,
        @Required @SerialName("Temperature") val temperature: Double
    )

    val state = Cbor.decodeFromByteArray<AppCoapState>(cbor)
    return AppState(
        AppMode.valueOf(state.mode),
        state.power,
        state.target,
        state.temperature,
    )
}

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
    FAILED_TO_UPDATE,
    FAILED_UNKNOWN,
    FAILED_NOT_PAIRED
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
    data class CoapPath(val method: String, val path: String) {}
    private val powerCoap = CoapPath("POST", "/thermostat/power")
    private val modeCoap = CoapPath("POST", "/thermostat/mode")
    private val targetCoap = CoapPath("POST", "/thermostat/target")
    private val appStateCoap = CoapPath("GET", "/thermostat")

    private var lastClientUpdate = System.nanoTime()
    private val _deviceState = MutableLiveData<AppState>()
    private val _connState: MutableLiveData<AppConnectionState> = MutableLiveData(AppConnectionState.INITIAL_CONNECTING)
    private val _connEvent: MutableLiveEvent<AppConnectionEvent> = MutableLiveEvent()
    private val _currentUser: MutableLiveData<IamUser> = MutableLiveData()
    private val _device = MutableLiveData<Device>()

    private val listener = ConnectionEventListener { event, _ -> onConnectionChanged(event) }

    private var pauseState = MutableStateFlow(false)
    private var updateLoopJob: Job? = null
    private lateinit var handle: ConnectionHandle

    // how many times per second should we request a state update from the device?
    private val updatesPerSecond = 2

    val deviceState: LiveData<AppState>
        get() = _deviceState

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
        updateLoopJob = viewModelScope.launch {
            pauseState.emit(false)
            val iam = IamUtil.create()

            try {
                val isPaired =
                    iam.isCurrentUserPaired(connectionManager.getConnection(handle))
                if (!isPaired) {
                    Log.i(TAG, "User connected to device but is not paired!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_NOT_PAIRED)
                    return@launch
                }

                updateDeviceState()

                if (_connState.value == AppConnectionState.DISCONNECTED) {
                    _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
                }
                _connState.postValue(AppConnectionState.CONNECTED)

                val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                _currentUser.postValue(user)

                updateLoop()
            } catch (e: IamException) {
                Log.w(TAG, "Update loop received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: NabtoRuntimeException) {
                Log.w(TAG, "Update loop received ${e.message}")
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: CancellationException) {
                Log.w(TAG, "Update loop was by CancellationException: ${e.message}")
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
                viewModelScope.launch { pauseState.emit(true) }
            }

            NabtoConnectionEvent.UNPAUSED -> {
                viewModelScope.launch { pauseState.emit(false) }
            }
            else -> {}
        }
    }

    private suspend fun updateDeviceState(): Boolean {
        val coap = connectionManager.createCoap(handle, appStateCoap.method, appStateCoap.path)

        try {
            coap.awaitExecute()
        } catch (e: NabtoRuntimeException) {
            Log.w(TAG, "Coap execute failed with $e")
            return false
        }

        val payload = coap.responsePayload
        val status = coap.responseStatusCode

        if (status != 205) {
            Log.w(TAG, "Coap execute got status code $status")
            return false
        }

        val state = decodeAppStateFromCBOR(payload)
        _deviceState.postValue(state)
        return true
    }

    private suspend fun updateLoop() {
        while (true) {
            // Suspend coroutine until pauseState is false.
            if (pauseState.value) {
                pauseState.first { !it }
            }

            if (!updateDeviceState()) continue

            val delayTime = (1.0 / updatesPerSecond.toDouble() * 1000.0).toLong()
            delay(delayTime)
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

    private suspend fun <T> safeCall(errorVal: T, code: suspend () -> T): T {
        return try {
            code()
        } catch (e: NabtoRuntimeException) {
            errorVal
        } catch (e: CancellationException) {
            Log.i(TAG, "safeCall was cancelled")
            errorVal
        }
    }

    /**
     * Set the power state on the device and update corresponding LiveData variables.
     */
    fun setPower(toggled: Boolean) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall(Unit) {
                val coap = connectionManager.createCoap(handle, powerCoap.method, powerCoap.path)
                val cbor = Cbor.encodeToByteArray(toggled)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
            }
        }
    }

    /**
     * Set the mode on the device and update corresponding LiveData variables.
     */
    fun setMode(mode: AppMode) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall(Unit) {
                val coap = connectionManager.createCoap(handle, modeCoap.method, modeCoap.path)
                val cbor = Cbor.encodeToByteArray(mode.string)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
            }
        }
    }

    /**
     * Set the target temperature on the device and update corresponding LiveData variables.
     */
    fun setTarget(target: Double) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall(Unit) {
                val coap = connectionManager.createCoap(handle, targetCoap.method, targetCoap.path)
                val cbor = Cbor.encodeToByteArray(target)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
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

    lateinit var initialConnectSpinner: View
    lateinit var lostConnectionBar: View
    lateinit var swipeRefreshLayout: SwipeRefreshLayout

    lateinit var powerSwitchView: SwitchMaterial
    lateinit var targetSliderView: Slider
    lateinit var targetTextView: TextView
    lateinit var modeSpinnerView: Spinner

    var userInteractedWithSpinner = false
    var lastConnectionState: AppConnectionState? = null

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

    private val waitSeconds = 10L
    private var timeSinceLastUserInteraction = Instant.now().minusSeconds(waitSeconds)

    private fun recordUserAction() {
        timeSinceLastUserInteraction = Instant.now()
    }

    private fun isProgrammaticUpdateAllowed(): Boolean {
        val elapsed = Duration.between(timeSinceLastUserInteraction, Instant.now())
        return elapsed.seconds >= waitSeconds
    }

    private fun setSpinnerSelection(position: Int) {
        userInteractedWithSpinner = false
        modeSpinnerView.setSelection(position)
    }

    private fun setInteractableViewsEnabled(enabled: Boolean) {
        val views = listOf(
            powerSwitchView,
            targetSliderView,
            targetTextView,
            modeSpinnerView
        )
        views.forEach { it.isEnabled = enabled }
    }

    private fun setupUI() {
        // Initialize various UI listeners

        // Listener for when the target temperature slider changes
        val format = R.string.target_format
        targetTextView.text = getString(format, targetSliderView.value.roundToInt())
        targetSliderView.addOnChangeListener { _, value, fromUser ->
            targetTextView.text = getString(R.string.target_format, value.roundToInt())
            if (fromUser) {
                recordUserAction()
            }
        }

        // Swipe down to call refresh()
        swipeRefreshLayout.setOnRefreshListener { refresh() }

        // Listeners for UI widgets...
        powerSwitchView.setOnCheckedChangeListener { _, isChecked ->
            model.setPower(isChecked)
            recordUserAction()
        }

        targetSliderView.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                recordUserAction()
            }
            override fun onStopTrackingTouch(slider: Slider) {
                model.setTarget(slider.value.toDouble())
                recordUserAction()
            }
        })

        modeSpinnerView.setOnTouchListener { view, _ ->
            userInteractedWithSpinner = true
            view.performClick()
            false
        }
        modeSpinnerView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!userInteractedWithSpinner) return
                if (pos >= 0 && pos < AppMode.values().size) {
                    val mode = AppMode.values()[pos]
                    model.setMode(mode)
                    recordUserAction()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
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

        initialConnectSpinner = view.findViewById(R.id.dp_loading)
        lostConnectionBar = view.findViewById(R.id.dp_lost_connection_bar)
        swipeRefreshLayout = view.findViewById(R.id.dp_swiperefresh)

        powerSwitchView = view.findViewById(R.id.dp_power_switch)
        targetSliderView = view.findViewById(R.id.dp_target_slider)
        targetTextView = view.findViewById(R.id.dp_target_temperature)
        modeSpinnerView = view.findViewById(R.id.dp_mode_spinner)

        // ArrayAdapter for modes spinner
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.modes_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinnerView.adapter = adapter
        }

        // Update the static text fields and toolbar title
        model.device.observe(viewLifecycleOwner, Observer {  device ->
            view.findViewById<TextView>(R.id.dp_info_appname).text = device.appName
            view.findViewById<TextView>(R.id.dp_info_devid).text = device.deviceId
            view.findViewById<TextView>(R.id.dp_info_proid).text = device.productId
            requireAppActivity().actionBarTitle = device.friendlyName
        })

        model.currentUser.observe(viewLifecycleOwner, Observer {
            view.findViewById<TextView>(R.id.dp_info_userid).text = it.username
        })

        model.deviceState.observe(viewLifecycleOwner, Observer { state ->
            view.findViewById<TextView>(R.id.dp_temperature).text = getString(R.string.temperature_format, state.temperature)

            if (isProgrammaticUpdateAllowed()) {
                val min = targetSliderView.valueFrom
                val max = targetSliderView.valueTo
                targetSliderView.value = state.target.toFloat().coerceIn(min, max)

                powerSwitchView.isChecked = state.power
                setSpinnerSelection(state.mode.ordinal)
            }
        })

        // We use this state to determine what the device page should look like
        model.connectionState.observe(viewLifecycleOwner, Observer {
            when (it) {
                AppConnectionState.INITIAL_CONNECTING -> {
                    swipeRefreshLayout.visibility = View.INVISIBLE
                    initialConnectSpinner.visibility = View.VISIBLE
                    setInteractableViewsEnabled(false)
                }
                AppConnectionState.CONNECTED -> {
                    swipeRefreshLayout.visibility = View.VISIBLE
                    initialConnectSpinner.visibility = View.GONE
                    lostConnectionBar.visibility = View.GONE
                    if (lastConnectionState == AppConnectionState.INITIAL_CONNECTING) {
                        setupUI()
                    }
                    setInteractableViewsEnabled(true)
                }
                AppConnectionState.DISCONNECTED -> {
                    lostConnectionBar.visibility = View.VISIBLE
                    setInteractableViewsEnabled(false)
                }
                null -> { }
            }

            lastConnectionState = it
        })

        // Device connection event handling
        model.connectionEvent.observe(viewLifecycleOwner) {
            when (it) {
                AppConnectionEvent.RECONNECTED -> { swipeRefreshLayout.isRefreshing = false }

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

                AppConnectionEvent.FAILED_TO_UPDATE -> {
                    view.snack(getString(R.string.device_page_failed_update))
                }

                AppConnectionEvent.FAILED_NOT_PAIRED -> {
                    view.snack(getString(R.string.device_failed_not_paired))
                    findNavController().popBackStack()
                }

                AppConnectionEvent.FAILED_UNKNOWN -> { }

                null -> { }
            }
        }
    }

    private fun refresh() {
        view?.findViewById<SwipeRefreshLayout>(R.id.dp_swiperefresh)?.isRefreshing = true
        model.tryReconnect()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_device, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_refresh) {
            refresh()
            return true
        }
        return false
    }
}