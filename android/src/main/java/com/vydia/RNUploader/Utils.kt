package com.vydia.RNUploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import net.gotev.uploadservice.okhttp.OkHttpStack
import okhttp3.OkHttpClient
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

fun buildHttpStack(network: Network?): OkHttpStack? {
  if(network == null) return null
  return OkHttpStack(
    OkHttpClient().newBuilder().run {
      followRedirects(true)
      followSslRedirects(true)
      retryOnConnectionFailure(true)
      connectTimeout(15L, TimeUnit.SECONDS)
      writeTimeout(30L, TimeUnit.SECONDS)
      readTimeout(30L, TimeUnit.SECONDS)
      cache(null)
      socketFactory(network.socketFactory)
      build()
    }
  )
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