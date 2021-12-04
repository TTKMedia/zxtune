package app.zxtune.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData
import app.zxtune.Logger

private val LOG = Logger(NetworkManager.javaClass.name)

object NetworkManager {

    @JvmStatic
    fun initialize(ctx: Context) {
        if (!this::connectionState.isInitialized) {
            connectionState = ConnectionState(ctx)
        }
    }

    private lateinit var connectionState: LiveData<Boolean>

    @JvmStatic
    val networkAvailable
        get() = connectionState
}

typealias NetworkStateCallback = (Boolean) -> Unit

private interface NetworkStateSource {
    fun isNetworkAvailable(): Boolean
    fun startMonitoring()
    fun stopMonitoring()
}

private class ConnectionState(ctx: Context) : LiveData<Boolean>() {

    private val source = if (Build.VERSION.SDK_INT >= 24) {
        NetworkStateApi24(ctx, this::update)
    } else {
        NetworkStatePre24(ctx, this::update)
    }

    init {
        update()
    }

    override fun getValue(): Boolean? {
        if (!hasActiveObservers()) {
            update()
        }
        return super.getValue()
    }

    private fun update() {
        value = source.isNetworkAvailable()
    }

    override fun onActive() = source.startMonitoring()
    override fun onInactive() = source.stopMonitoring()

    private fun update(newState: Boolean) = postValue(newState).also {
        LOG.d { "NetworkState=${newState}" }
    }
}

// Based on solutions from
// https://stackoverflow.com/questions/36421930/connectivitymanager-connectivity-action-deprecated
@RequiresApi(24)
private class NetworkStateApi24(ctx: Context, private val cb: NetworkStateCallback) :
    NetworkStateSource {

    private val manager = ctx.getConnectivityManager()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = cb.invoke(true)
        override fun onLost(network: Network) = cb.invoke(false)
    }

    override fun isNetworkAvailable() = manager?.activeNetwork?.let { network ->
        manager.getNetworkCapabilities(network)?.run {
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        }
    } ?: false

    override fun startMonitoring() =
        manager?.registerDefaultNetworkCallback(networkCallback) ?: Unit

    override fun stopMonitoring() = manager?.unregisterNetworkCallback(networkCallback) ?: Unit
}

@Suppress("DEPRECATION")
private class NetworkStatePre24(private val ctx: Context, private val cb: NetworkStateCallback) :
    NetworkStateSource {

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val isNoConnectivity =
                    intent?.extras?.getBoolean(ConnectivityManager.EXTRA_NO_CONNECTIVITY) ?: true
                cb.invoke(!isNoConnectivity)
            }
        }
    }

    override fun isNetworkAvailable() =
        true == ctx.getConnectivityManager()?.activeNetworkInfo?.isConnected

    override fun startMonitoring() {
        ctx.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    override fun stopMonitoring() = ctx.unregisterReceiver(receiver)
}

private fun Context.getConnectivityManager() =
    getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
