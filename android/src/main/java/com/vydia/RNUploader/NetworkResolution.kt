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
  var currentBestNetwork: Network? = null

  val request = NetworkRequest.Builder().run {
    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    if (discretionary) {
      addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } else {
      addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
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
      setBestNetwork(network)
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
      setBestNetwork((network))
    }

    override fun onLost(network: Network) {
      setBestNetwork((network))
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
      setBestNetwork((network))
    }

    override fun onCapabilitiesChanged(
      network: Network,
      networkCapabilities: NetworkCapabilities
    ) {
      setBestNetwork(network)
    }
  }

  connectivityManager.requestNetwork(request, callback)

  awaitClose {
    connectivityManager.unregisterNetworkCallback(callback)
  }

}
  // we don't want to switch back and forth too quickly between networks
  // since it will massively disrupt the uploader
  .debounce(1000)


//addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
//addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
//if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//  addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
//}

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