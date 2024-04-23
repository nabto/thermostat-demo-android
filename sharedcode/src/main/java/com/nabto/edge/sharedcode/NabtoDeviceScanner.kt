package com.nabto.edge.sharedcode

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nabto.edge.client.MdnsResult
import com.nabto.edge.client.MdnsScanner
import com.nabto.edge.client.NabtoClient

class NabtoDeviceScanner(nabtoClient: NabtoClient) {
    private val deviceMap = HashMap<String, Device>()
    private val _devices: MutableLiveData<List<Device>> = MutableLiveData()
    private val scanner: MdnsScanner = nabtoClient.createMdnsScanner()
    val devices: LiveData<List<Device>>
        get() = _devices

    init {
        scanner.addMdnsResultReceiver { result ->
            when (result?.action) {
                MdnsResult.Action.ADD,
                MdnsResult.Action.UPDATE -> {
                    deviceMap[result.serviceInstanceName] =
                        Device(result.productId, result.deviceId)
                }
                MdnsResult.Action.REMOVE -> {
                    deviceMap.remove(result.serviceInstanceName)
                }
                else -> {}
            }
            _devices.postValue(ArrayList(deviceMap.values))
        }
        scanner.start()
    }
}