package com.vydia.RNUploader

import android.net.*
import android.os.Build

class NetworkResolution(
  private val connectivityManager: ConnectivityManager,
  private val onBestNetworkChange: (network: Network?, discretionary: Boolean) -> Unit
) {
  private var smartNetworkResolutionEnabled = false
  private val unregisterDefaultNetworkListeners: () -> Unit

  init {
    val unregisterNonDiscretionaryNetworkListener =
      pickBestNetwork(connectivityManager, discretionary = false, bindProcessToNetwork = false) {
        onBestNetworkChange(it, false)
      }

    val unregisterDiscretionaryNetworkListener =
      pickBestNetwork(connectivityManager, discretionary = true, bindProcessToNetwork = false) {
        onBestNetworkChange(it, true)
      }


    unregisterDefaultNetworkListeners = {
      unregisterDiscretionaryNetworkListener()
      unregisterNonDiscretionaryNetworkListener()
    }
  }

  fun enableSmartNetworkResolution() {
    if (smartNetworkResolutionEnabled) return
    smartNetworkResolutionEnabled = true
    unregisterDefaultNetworkListeners()

    pickBestNetwork(connectivityManager, discretionary = false, bindProcessToNetwork = true) {
      onBestNetworkChange(it, false)
    }

    pickBestNetwork(connectivityManager, discretionary = true, bindProcessToNetwork = true) {
      onBestNetworkChange(it, true)
    }
  }
}


fun pickBestNetwork(
  connectivityManager: ConnectivityManager,
  discretionary: Boolean,
  bindProcessToNetwork: Boolean,
  onChange: (network: Network?) -> Unit
): () -> Unit {
  var wifi: Network? = null
  var cellular: Network? = null
  var bestNetwork: Network? = null

  val wifiRequest = NetworkRequest.Builder().run {
    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    if (discretionary)
      addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    build()
  }

  val cellularRequest = NetworkRequest.Builder().run {
    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    build()
  }

  fun setBestNetwork() {
    var network = wifi
    if (!discretionary && cellular != null && wifi == null)
      network = cellular

    if (network == bestNetwork) return
    bestNetwork = network
    onChange(bestNetwork)
  }


  val unregisterWifiCallback = observeNetwork(connectivityManager, wifiRequest) {
    wifi = it
    if (bindProcessToNetwork) wifi?.let { connectivityManager.bindProcessToNetwork(wifi) }
    setBestNetwork()
  }

  var unregisterCellularCallback: (() -> Unit)? = null
  if (!discretionary)
    unregisterCellularCallback = observeNetwork(connectivityManager, cellularRequest) {
      cellular = it
      if (bindProcessToNetwork) cellular?.let { connectivityManager.bindProcessToNetwork(cellular) }
      setBestNetwork()
    }

  return {
    unregisterWifiCallback()
    unregisterCellularCallback?.let { it() }
  }
}

private fun observeNetwork(
  connectivityManager: ConnectivityManager,
  request: NetworkRequest,
  onChange: (network: Network?) -> Unit
): () -> Unit {
  var current: Network? = null
  fun setNetwork(network: Network?) {
    val new = network.let {
      if (it == null) null
      else if (validateNetwork(it, connectivityManager)) network
      else null
    }

    if (new == current) return
    current = new
    onChange(new)
  }

  val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      setNetwork(network)
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
      setNetwork(null)
    }

    override fun onLost(network: Network) {
      setNetwork(null)
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      if (blocked) setNetwork(null) else setNetwork(network)
    }

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      setNetwork(network)
    }
  }
  // using `requestNetwork` as it allows keeping cellular networks available when wifi is connected
  connectivityManager.requestNetwork(request, callback)

  return {
    connectivityManager.unregisterNetworkCallback(callback)
  }
}


private fun validateNetwork(
  network: Network,
  connectivityManager: ConnectivityManager
): Boolean {
  val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) return false
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return false
  } else {
    // From @react-native-community/net-info
    // This may return null per API docs, and is deprecated, but for older APIs (< VERSION_CODES.P)
    // we need it to test for suspended internet
    val networkInfo = connectivityManager.getNetworkInfo(network) ?: return false
    if (networkInfo.detailedState != NetworkInfo.DetailedState.CONNECTED) return false
  }


  return true
}

