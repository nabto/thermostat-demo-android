package com.nabto.edge.sharedcode

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope

/**
 * Interface for getting Nabto-relevant data.
 *
 * Upon first running the app a private key is created and stored in the shared preferences of
 * the android phone. This private key can be retrieved with [getClientPrivateKey]
 */
interface NabtoRepository {

    /**
     * Get the private key of the client that was created using NabtoClient.createPrivateKey.
     */
    fun getClientPrivateKey(): String

    /**
     * Deletes the currently stored private key and generates a new one to replace it.
     */
    fun resetClientPrivateKey()

    /**
     * Returns a list of Nabto devices that have been discovered through mDNS
     */
    fun getScannedDevices(): LiveData<List<Device>>

    /**
     * Returns an application-wide CoroutineScope.
     */
    fun getApplicationScope(): CoroutineScope

    /**
     * Returns the display name of the user as LiveData.
     */
    fun getDisplayName(): LiveData<String>

    /**
     * Set the display name of the user.
     */
    fun setDisplayName(displayName: String)

    /**
     * Get Client SDK version
     */
    fun getClientVersion(): String
}

class NabtoRepositoryImpl(
    private val context: Context,
    private val nabtoClient: NabtoClient,
    private val scope: CoroutineScope,
    private val scanner: NabtoDeviceScanner
) : NabtoRepository {
    private val _displayName = MutableLiveData<String>()
    private val pref = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        run {
            // Store a client private key to be used for connections.
            val key = PreferenceKeys.clientPrivateKey
            if (!pref.contains(key)) {
                val pk = nabtoClient.createPrivateKey()
                with(pref.edit()) {
                    putString(key, pk)
                    apply()
                }
            }
        }


        run {
            val key = PreferenceKeys.displayName
            if (!pref.contains(key)) {
                val name = if (Build.VERSION.SDK_INT <= 31) {
                    Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                } else {
                    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                }
                with(pref.edit()) {
                    putString(key, name)
                    apply()
                }
            }

            pref.getString(key, null)?.let {
                _displayName.postValue(it)
            }
        }
    }

    override fun getClientPrivateKey(): String {
        val key = PreferenceKeys.clientPrivateKey
        if (pref.contains(key)) {
            return pref.getString(key, null)!!
        } else {
            // @TODO: Replace this with an exception of our own that can have more context
            throw RuntimeException("Attempted to access client's private key, but it was not found.")
        }
    }

    override fun resetClientPrivateKey() {
        val key = PreferenceKeys.clientPrivateKey
        val pk = nabtoClient.createPrivateKey()
        with(pref.edit()) {
            putString(key, pk)
            apply()
        }
    }

    // @TODO: Let application scope be injected instead of having to go through NabtoRepository?
    override fun getApplicationScope(): CoroutineScope {
        return scope
    }

    override fun getDisplayName(): LiveData<String> {
        return _displayName
    }

    override fun setDisplayName(displayName: String) {
        _displayName.postValue(displayName)
        val key = PreferenceKeys.displayName
        with(pref.edit()) {
            putString(key, displayName)
            apply()
        }
    }

    override fun getClientVersion(): String {
        return nabtoClient.version()
    }

    override fun getScannedDevices(): LiveData<List<Device>> {
        return scanner.devices
    }
}
