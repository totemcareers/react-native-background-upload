package com.vydia.RNUploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.channels.FileChannel


/*
 * Validate network connectivity
 * Inspired by react-native-community/netinfo
 */
fun validateNetwork(discretionary: Boolean, connectivityManager: ConnectivityManager): Boolean {
  // TODO check if this picks Cellular if Wifi isn't internet-reachable
  val network = connectivityManager.activeNetwork
  val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

  // Get the connection type
  if (discretionary && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    return false

  // This may return null per API docs, and is deprecated, but for older APIs (< VERSION_CODES.P)
  // we need it to test for suspended internet
  val networkInfo = connectivityManager.getNetworkInfo(network)

  // Check to see if the network is temporarily unavailable or if airplane mode is toggled on
  var isInternetSuspended = false
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    isInternetSuspended =
      !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
  } else if (networkInfo != null) {
    isInternetSuspended = networkInfo.detailedState != NetworkInfo.DetailedState.CONNECTED
  }

  if (isInternetSuspended) return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
  if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false

  return true
}

fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int): ReadableArray {
  val file = RandomAccessFile(parentFilePath, "r")

  val numBytes = file.length();
  val chunkSize = numBytes / numChunks + if (numBytes % numChunks > 0) 1 else 0
  val chunkRanges = Arguments.createArray();


  runBlocking(Dispatchers.IO) {
    for (i in 0 until numChunks) {
      val outputPath = chunkDirPath.plus("/").plus(i.toString());
      val outputFile = RandomAccessFile(outputPath, "rw");

      val rangeStart = chunkSize * i;
      var rangeLength = numBytes - rangeStart;
      if (rangeLength > chunkSize) rangeLength = chunkSize;

      chunkRanges.pushMap(Arguments.createMap().apply {
        putString("position", rangeStart.toString());
        putString("size", rangeLength.toString());
      })

      launch {
        val input = file.channel.map(FileChannel.MapMode.READ_ONLY, rangeStart, rangeLength);
        val output = outputFile.channel.map(FileChannel.MapMode.READ_WRITE, 0, rangeLength);
        output.put(input);
      }
    }
  }

  return chunkRanges
}

fun initializeNotificationChannel(notificationChannel: String, manager: NotificationManager) {
  if (Build.VERSION.SDK_INT < 26) return

  val channel = NotificationChannel(
    notificationChannel,
    "Background Upload Channel",
    NotificationManager.IMPORTANCE_LOW
  )

  manager.createNotificationChannel(channel)
}