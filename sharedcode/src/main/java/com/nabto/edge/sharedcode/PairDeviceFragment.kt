package com.nabto.edge.sharedcode

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.nabto.edge.client.Connection
import com.nabto.edge.client.ErrorCode
import com.nabto.edge.client.ErrorCodes
import com.nabto.edge.client.NabtoNoChannelsException
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.iamutil.*
import com.nabto.edge.iamutil.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

private class PairDeviceViewModelFactory(
    private val manager: NabtoConnectionManager,
    private val device: Device
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            NabtoConnectionManager::class.java,
            Device::class.java
        ).newInstance(manager, device)
    }
}

private sealed class PairingResult {
    /**
     * The device was either already paired or has just been paired.
     */
    data class Success(val alreadyPaired: Boolean, val dev: Device) : PairingResult()

    /**
     * The device's app name does not match what was expected
     */
    object FailedIncorrectApp : PairingResult()

    /**
     * The chosen username is already registered with the device.
     */
    object FailedUsernameExists : PairingResult()

    /**
     * The available pairing modes on the device are not supported.
     */
    object FailedInvalidPairingMode : PairingResult()

    /**
     * Attempted an open password pairing but no password was provided.
     */
    object FailedNoPassword : PairingResult()

    /**
     * @TODO: FailedNoChannels with the current setup will never be used, see SC-1744
     */
    object FailedNoChannels : PairingResult()

    /**
     * Coroutine was somehow cancelled during pairing
     */
    object FailedCoroutineCancelled : PairingResult()

    /**
     * Device was disconnected during pairing
     */
    object FailedDeviceDisconnected : PairingResult()

    /**
     * Device failed to connect for pairing.
     */
    data class FailedDeviceConnectFail(val localEc: ErrorCode, val remoteEc: ErrorCode, val directCandidatesEc: ErrorCode) : PairingResult()

    /**
     * Device connection closed during pairing.
     */
    object FailedDeviceClosed : PairingResult()

    /**
     * Pairing failed for some other reason.
     */
    data class Failed(val cause: String) : PairingResult()
}

/**
 * PairDeviceViewModel's responsibility is to open a connection using [NabtoConnectionManager]
 * and then enact the pairing flow.
 */
private class PairDeviceViewModel(
    private val manager: NabtoConnectionManager,
    private val device: Device
    ) : ViewModel() {
    private val TAG = "PairDeviceViewModel"
    private var password = ""
    private lateinit var listener: ConnectionEventListener
    private lateinit var handle: ConnectionHandle
    private val iam = IamUtil.create()
    private var observer: Observer<NabtoConnectionState>? = null

    private val _pairingResult = MutableLiveData<PairingResult>()
    val pairingResult: LiveData<PairingResult>
        get() = _pairingResult

    private suspend fun passwordAuthenticate(pw: String) {
        password = pw
        withContext(Dispatchers.IO) {
            manager.getConnection(handle).passwordAuthenticate("", password)
        }
    }

    private suspend fun isCurrentUserPaired(): Boolean {
        return iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
    }

    private suspend fun getPairingDetails(): DeviceDetails {
        val connection = manager.getConnection(handle)
        return iam.awaitGetDeviceDetails(connection)
    }

    private suspend fun getDeviceDetails(friendlyDeviceName: String): Device? {
        try {
            val connection = manager.getConnection(handle)
            val details = iam.awaitGetDeviceDetails(connection)
            val user = iam.awaitGetCurrentUser(connection)
            val fingerprint = connection.deviceFingerprint
            return device.copy(
                productId = details.productId,
                deviceId = details.deviceId,
                fingerprint = fingerprint,
                SCT = user.sct,
                appName = details.appName ?: "",
                friendlyName = friendlyDeviceName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not retrieve device details due to $e")
            return null
        }
    }

    private suspend fun pairLocalOpen(desiredUsername: String, friendlyDeviceName: String): Device? {
        iam.awaitPairLocalOpen(manager.getConnection(handle), desiredUsername)
        return getDeviceDetails(friendlyDeviceName)
    }

    suspend fun pairPasswordOpen(desiredUsername: String, friendlyDeviceName: String): Device? {
        iam.awaitPairPasswordOpen(manager.getConnection(handle), desiredUsername, password)
        return getDeviceDetails(friendlyDeviceName)
    }

    suspend fun updateDisplayName(username: String, displayName: String) {
        iam.awaitUpdateUserDisplayName(manager.getConnection(handle), username, displayName)
    }

    private fun pairAndUpdateDevice(username: String, friendlyName: String, displayName: String) {
        viewModelScope.launch {
            try {
                val pairingDetails = getPairingDetails()

                if (isCurrentUserPaired()) {
                    val dev = getDeviceDetails(friendlyName)
                    if (dev != null) {
                        _pairingResult.postValue(PairingResult.Success(true, dev))
                        return@launch
                    } else {
                        _pairingResult.postValue(PairingResult.FailedDeviceDisconnected)
                    }
                }

                val modes = iam.getAvailablePairingModes(manager.getConnection(handle))

                // this list decides which modes will be tried in order
                val supportedModes = listOf(
                    PairingMode.PASSWORD_OPEN,
                    PairingMode.LOCAL_OPEN
                )

                if (supportedModes.intersect(modes.toSet()).isEmpty()) {
                    _pairingResult.postValue(PairingResult.FailedInvalidPairingMode)
                    return@launch
                }

                if (modes.count() == 1 && modes.contains(PairingMode.PASSWORD_OPEN)) {
                    device.password.ifEmpty {
                        _pairingResult.postValue(PairingResult.FailedNoPassword)
                    }
                }

                var dev: Device? = null
                for (mode in supportedModes) {
                    if (dev != null) {
                        break
                    }

                    dev = when (mode) {
                        PairingMode.LOCAL_OPEN -> {
                            pairLocalOpen(username, friendlyName)
                        }

                        PairingMode.PASSWORD_OPEN -> {
                            if (device.password.isNotEmpty()) {
                                passwordAuthenticate(device.password)
                                pairPasswordOpen(username, friendlyName)
                            } else null
                        }

                        else -> null
                    }
                }

                dev?.let {
                    updateDisplayName(username, displayName)
                    _pairingResult.postValue(PairingResult.Success(false, it))
                } ?: run {
                    _pairingResult.postValue(PairingResult.FailedInvalidPairingMode)
                }
            } catch (e: IamException) {
                // You could carry some extra information in PairingResult.Failed using the info in the exception
                // to give a better update to the end user
                if (e.error == IamError.USERNAME_EXISTS) {
                    _pairingResult.postValue(PairingResult.FailedUsernameExists)
                } else {
                    _pairingResult.postValue(PairingResult.Failed(e.error.toString()))
                }
                Log.i(TAG, "Attempted pairing failed with $e")
            } catch (e: NabtoRuntimeException) {
                if (e.errorCode.errorCode == ErrorCodes.NO_CHANNELS) {
                    _pairingResult.postValue(PairingResult.FailedNoChannels)
                } else {
                    _pairingResult.postValue(PairingResult.Failed(e.errorCode.name))
                }
                manager.unsubscribe(handle, listener)
                manager.releaseHandle(handle)
                Log.i(TAG, "Attempted pairing failed with $e")
            } catch (e: CancellationException) {
                _pairingResult.postValue(PairingResult.FailedCoroutineCancelled)
                manager.unsubscribe(handle, listener)
                manager.releaseHandle(handle)
            }
        }
    }

    /**
     * Initiates pairing with device.
     *
     * @param[username] the user's chosen username.
     * @param[friendlyName] the user's chosen name for the device, will be stored in database.
     * @param[displayName] the display                manager.releaseHandle(handle) name that the device will keep for this user.
     */
    fun initiatePairing(username: String, friendlyName: String, displayName: String) {
        viewModelScope.launch {
            listener = ConnectionEventListener { event, _ ->
                when (event) {
                    NabtoConnectionEvent.CONNECTED -> {
                        // viewModelScope.launch { pairAndUpdateDevice(username, friendlyName, displayName) }
                    }
                    NabtoConnectionEvent.DEVICE_DISCONNECTED -> {
                        _pairingResult.postValue(PairingResult.FailedDeviceDisconnected)
                    }
                    NabtoConnectionEvent.FAILED_TO_CONNECT -> {
                        val conn = manager.getConnection(handle)
                        val localEc = conn.localChannelErrorCode
                        val remoteEc = conn.remoteChannelErrorCode
                        val directCandidatesEc = conn.directCandidatesChannelErrorCode
                        _pairingResult.postValue(PairingResult.FailedDeviceConnectFail(localEc, remoteEc, directCandidatesEc))
                    }
                    NabtoConnectionEvent.CLOSED -> {
                        _pairingResult.postValue(PairingResult.FailedDeviceClosed)
                    }
                    else -> {}
                }
            }
            handle = manager.requestConnection(device)
            observer = Observer<NabtoConnectionState> {
                if (it == NabtoConnectionState.CONNECTED) {
                    pairAndUpdateDevice(username, friendlyName, displayName)
                }
            }
            manager.getConnectionState(handle)?.let { stateLiveData ->
                observer?.let {
                    val newObserver = Observer<NabtoConnectionState> { state ->
                        if (state == NabtoConnectionState.CONNECTED) {
                            pairAndUpdateDevice(username, friendlyName, displayName)
                        }
                    }
                    stateLiveData.removeObserver(it)
                    stateLiveData.observeForever(newObserver)
                    observer = newObserver
                }
            }
            manager.subscribe(handle, listener)
            manager.connect(handle)
        }
    }
}

/**
 * Fragment for fragment_device_page.xml
 * When a user wants to pair with a specific device, they land on this fragment.
 *
 * When navigating to this fragment there must be a passed a bundle carrying PairingData
 * to the fragment. This can be done with PairingData.makeBundle
 */
class PairDeviceFragment : Fragment() {
    private val TAG = "PairDeviceFragment"
    private val repo: NabtoRepository by inject()
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()
    private val model: PairDeviceViewModel by viewModels {
        val device = requireArguments().let {
            Device(
                productId = it.getString("productId") ?: "",
                deviceId = it.getString("deviceId") ?: "",
                password = it.getString("password") ?: "",
                SCT = it.getString("sct") ?: ""
            )
        }
        PairDeviceViewModelFactory(manager, device)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pair_device, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val button = view.findViewById<Button>(R.id.complete_pairing)

        model.pairingResult.observe(viewLifecycleOwner, Observer { result ->
            val snack = when (result) {
                is PairingResult.Success -> {
                    // Success, update the local database of devices
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = database.deviceDao()
                        dao.insertOrUpdate(result.dev)
                    }
                    getString(if (result.alreadyPaired) {
                        R.string.pair_device_already_paired
                    } else {
                        R.string.pair_device_success
                    })
                }

                is PairingResult.FailedIncorrectApp -> getString(R.string.pair_device_failed_incorrect_app)
                is PairingResult.FailedUsernameExists -> getString(R.string.pair_device_failed_username_exists)
                is PairingResult.FailedNoPassword -> getString(R.string.pair_device_failed_no_password)
                is PairingResult.FailedInvalidPairingMode -> getString(R.string.pair_device_failed_invalid_pairing_modes)
                is PairingResult.Failed -> getString(R.string.pair_device_failed, result.cause)
                is PairingResult.FailedCoroutineCancelled -> getString(R.string.pair_device_failed_coroutine)
                is PairingResult.FailedDeviceClosed -> getString(R.string.pair_device_failed_closed)
                is PairingResult.FailedDeviceConnectFail -> {
                    var error = ""
                    if (result.localEc.errorCode != ErrorCodes.NONE) error += "\nLocal error: ${result.localEc.name}"
                    if (result.remoteEc.errorCode != ErrorCodes.NONE) error += "\nremote error: ${result.remoteEc.name}"
                    if (result.directCandidatesEc.errorCode != ErrorCodes.NONE) error += "\ndirect candidates error: ${result.directCandidatesEc.name}"
                    getString(R.string.pair_device_failed_to_connect, error)
                }
                is PairingResult.FailedDeviceDisconnected -> getString(R.string.pair_device_failed_disconnected)
                is PairingResult.FailedNoChannels -> getString(R.string.pair_device_failed_no_channels)
            }

            view.snack(snack)
            when (result) {
                is PairingResult.Success -> {
                    findNavController().navigate(AppRoute.home()) {
                        popUpTo(AppRoute.home())
                    }
                }
                is PairingResult.FailedUsernameExists -> { button.isEnabled = true }
                else -> { findNavController().popBackStack() }
            }
        })

        val etUsername = view.findViewById<EditText>(R.id.pair_device_username)
        val etFriendlyName = view.findViewById<EditText>(R.id.pair_device_friendlyname)

        val cleanedUsername = (repo.getDisplayName().value ?: "").filter { it.isLetterOrDigit() }.lowercase()
        etUsername.setText(cleanedUsername)

        button.setOnClickListener { _ ->
            clearFocusAndHideKeyboard()
            val username = etUsername.text.toString()
            val friendlyName = etFriendlyName.text.toString()

            if (username.isEmpty())
            {
                etUsername.error = getString(R.string.pair_device_error_username_empty)
                return@setOnClickListener
            }

            val isValid = username.all { it.isDigit() || it.isLowerCase() }
            if (!isValid) {
                etUsername.error = getString(R.string.pair_device_error_username_invalid)
                return@setOnClickListener
            }

            if (friendlyName.isEmpty()) {
                etFriendlyName.error = getString(R.string.pair_device_error_friendlyname_empty)
                return@setOnClickListener
            }

            button.isEnabled = false
            model.initiatePairing(username, etFriendlyName.text.toString(), repo.getDisplayName().value ?: username)
        }
    }
}