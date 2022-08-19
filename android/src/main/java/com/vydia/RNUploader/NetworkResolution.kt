package com.vydia.RNUploader

import android.net.*
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce

fun observeBestNetwork(
  connectivityManager: ConnectivityManager,
  discretionary: Boolean,
) = callbackFlow<Network?> {
  val networks = mutableSetOf<Network>()
  var currentBestNetwork: Network? = null


  val request = NetworkRequest.Builder().run {
    addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }
    if (discretionary) {
      addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    build()
  }

  fun setBestNetwork(network: Network?) {
    val network = network ?: connectivityManager.activeNetwork
    if (network == currentBestNetwork) return
    currentBestNetwork = network
    trySendBlocking(network)
  }

  val callback = object :
    ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      networks.add(network)
      setBestNetwork(computeBestNetwork(connectivityManager, networks))
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
      networks.remove(network)
      setBestNetwork(computeBestNetwork(connectivityManager, networks))
    }

    override fun onLost(network: Network) {
      networks.remove(network)
      setBestNetwork(computeBestNetwork(connectivityManager, networks))
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      if (blocked) networks.remove(network)
      else networks.add(network)
      setBestNetwork(computeBestNetwork(connectivityManager, networks))
    }

    override fun onCapabilitiesChanged(
      network: Network,
      networkCapabilities: NetworkCapabilities
    ) {
      setBestNetwork(computeBestNetwork(connectivityManager, networks))
    }
  }

  connectivityManager.registerNetworkCallback(request, callback)

  awaitClose {
    connectivityManager.unregisterNetworkCallback(callback)
  }

}.debounce(1000)


private fun computeBestNetwork(
  connectivityManager: ConnectivityManager,
  candidates: Set<Network>,
): Network? {
  var candidates = candidates.filter { !checkSuspendedStatusWithLegacyAPI(it, connectivityManager) }

  candidates.find {
    connectivityManager.activeNetwork == it
  }?.let { return it }

  // When the device is connected to wifi, the wifi becomes the active network,
  // but it's not guaranteed to have internet connections, which means it won't
  // be in our list of bestNetworks.
  //
  // So as a fallback, we'll have to select the best network from the list

  // If there are trusted networks, only use them
  candidates.filter {
    connectivityManager.getNetworkCapabilities(it)
      ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
      ?: true
  }.let { if (it.isNotEmpty()) candidates = it }

  // If there are un-metered networks, only use them
  candidates.filter {
    connectivityManager.getNetworkCapabilities(it)
      ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      ?: true
  }.let { if (it.isNotEmpty()) candidates = it }

  // If there are un-restricted networks, only use them
  candidates.filter {
    connectivityManager.getNetworkCapabilities(it)
      ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
      ?: true
  }.let { if (it.isNotEmpty()) candidates = it }

  // If there are non-roaming networks, only use them
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    candidates.filter {
      connectivityManager.getNetworkCapabilities(it)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        ?: true
    }.let { if (it.isNotEmpty()) candidates = it }

  // If there are wifi networks, only use them
  candidates.filter {
    connectivityManager.getNetworkCapabilities(it)
      ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
      ?: true
  }.let { if (it.isNotEmpty()) candidates = it }

  return candidates.maxByOrNull {
    connectivityManager.getNetworkCapabilities(it)
      ?.linkUpstreamBandwidthKbps
      ?: 0
  }
}

private fun checkSuspendedStatusWithLegacyAPI(
  network: Network,
  connectivityManager: ConnectivityManager
): Boolean {
  // if API level is larger than P, we already have NET_CAPABILITY_NOT_SUSPENDED above
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
  // From @react-native-community/net-info
  // This may return null per API docs, and is deprecated, but for older APIs (< VERSION_CODES.P)
  // we need it to test for suspended internet
  val networkInfo = connectivityManager.getNetworkInfo(network) ?: return false
  return networkInfo.detailedState != NetworkInfo.DetailedState.CONNECTED
}