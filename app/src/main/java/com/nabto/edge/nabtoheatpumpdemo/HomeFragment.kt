package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitIsCurrentUserPaired
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject


enum class HomeDeviceItemStatus {
    ONLINE,
    CONNECTING,
    OFFLINE,
    UNPAIRED
}

class HomeViewModelFactory(
    private val database: DeviceDatabase,
    private val manager: NabtoConnectionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            DeviceDatabase::class.java,
            NabtoConnectionManager::class.java
        ).newInstance(database, manager)
    }
}

class HomeViewModel(
    private val database: DeviceDatabase,
    private val manager: NabtoConnectionManager
) : ViewModel() {
    private val TAG = javaClass.simpleName

    data class HomeDeviceItem(
        //var observer: Observer<NabtoConnectionState>?,
        val device: Device,
        //val handle: ConnectionHandle,
        val status: HomeDeviceItemStatus
    )

    private val _deviceList = MutableLiveData<List<HomeDeviceItem>>()
    val deviceList: LiveData<List<HomeDeviceItem>>
        get() = _deviceList

    private val connections = mutableMapOf<Device, ConnectionHandle>()
    private val status = mutableMapOf<Device, HomeDeviceItemStatus>()

    //private val connections = mutableMapOf<Device, HomeDeviceItem>()

    private var devices: List<Device> = listOf()

    init {
        Log.i(TAG, "Initializing...")
        viewModelScope.launch {
            database.deviceDao().getAll().collect { devs ->
                this@HomeViewModel.devices = devs
                sync()
            }
        }
    }

    fun sync() {
        fun post() {
            val list = devices.map {
                HomeDeviceItem(it, status.getOrDefault(it, HomeDeviceItemStatus.OFFLINE))
            }
            _deviceList.postValue(list)
        }

        for (dev in devices) {
            status[dev] = status[dev] ?: HomeDeviceItemStatus.OFFLINE
            connections[dev] = connections[dev] ?: manager.requestConnection(dev) { event, handle ->
                viewModelScope.launch {
                    status[dev] = when (event) {
                        NabtoConnectionEvent.CONNECTING -> HomeDeviceItemStatus.CONNECTING
                        NabtoConnectionEvent.CONNECTED -> {
                            val iam = IamUtil.create()
                            val paired = iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
                            if (paired) {
                                HomeDeviceItemStatus.ONLINE
                            } else {
                                HomeDeviceItemStatus.UNPAIRED
                            }
                        }
                        else -> HomeDeviceItemStatus.OFFLINE
                    }
                    post()
                }
            }
        }

        post()
    }

    fun reconnect() {
        for ((_, handle) in connections) {
            manager.reconnect(handle)
        }
    }

    fun release() {
        for ((_, handle) in connections) {
            manager.releaseHandle(handle)
        }
        connections.clear()
        status.clear()
    }
}

class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
        val connectionStatusView: ImageView = view.findViewById(R.id.home_device_item_connection)
    }

    private var dataSet: List<HomeViewModel.HomeDeviceItem> = listOf()

    fun submitDeviceList(devices: List<HomeViewModel.HomeDeviceItem>) {
        dataSet = devices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_device_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.title.text = dataSet[position].device.getDeviceNameOrElse("Unnamed device")
        holder.status.text = dataSet[position].device.deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position].device)
        }
        val (color, icon) = when (dataSet[position].status) {
            HomeDeviceItemStatus.ONLINE -> R.color.green to R.drawable.ic_baseline_check_circle
            HomeDeviceItemStatus.UNPAIRED -> R.color.yellow to R.drawable.ic_baseline_lock
            HomeDeviceItemStatus.CONNECTING -> R.color.red to R.drawable.ic_baseline_warning
            HomeDeviceItemStatus.OFFLINE -> R.color.red to R.drawable.ic_baseline_warning
        }
        holder.connectionStatusView.setImageResource(icon)
        holder.connectionStatusView.setColorFilter(
            ContextCompat.getColor(
                holder.view.context,
                color
            )
        )
    }

    override fun getItemCount() = dataSet.size
}


class HomeFragment : Fragment(), MenuProvider {
    private val TAG = javaClass.simpleName

    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()
    private val model: HomeViewModel by viewModels {
        HomeViewModelFactory(database, manager)
    }
    private val deviceListAdapter = DeviceListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        val layoutManager = LinearLayoutManager(activity)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = layoutManager

        view.findViewById<Button>(R.id.home_pair_new_button).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_pairNewFragment)
        }

        model.deviceList.observe(viewLifecycleOwner, Observer { devices ->
            deviceListAdapter.submitDeviceList(devices)
            view.findViewById<View>(R.id.home_empty_layout).visibility =
                if (devices.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        })

        val dividerItemDecoration = DividerItemDecoration(
            recycler.context,
            layoutManager.orientation
        )
        recycler.addItemDecoration(dividerItemDecoration)

        model.sync()
    }

    fun onDeviceClick(device: Device) {
        model.release()
        val title = device.friendlyName.ifEmpty { getString(R.string.unnamed_device) }
        val bundle = bundleOf("device" to device, "title" to title)
        findNavController().navigate(R.id.action_homeFragment_to_devicePageFragment, bundle)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_pair_new) {
            model.release()
            findNavController().navigate(R.id.action_homeFragment_to_pairNewFragment)
            return true
        }

        if (menuItem.itemId == R.id.action_device_refresh) {
            model.reconnect()
            return true
        }

        if (menuItem.itemId == R.id.action_settings) {
            model.release()
            findNavController().navigate(R.id.action_homeFragment_to_appSettingsFragment)
        }
        return false
    }
}