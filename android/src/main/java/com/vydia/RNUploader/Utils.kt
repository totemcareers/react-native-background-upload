package com.vydia.RNUploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Network
import android.os.Build
import com.facebook.react.bridge.ReadableArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import net.gotev.uploadservice.okhttp.OkHttpStack
import okhttp3.OkHttpClient
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

fun buildHttpStack(network: Network?): OkHttpStack? {
  if (network == null) return null
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

class Chunk(val position: Long, val size: Long, val path: String) {
  companion object {
    fun fromReactMethodParams(paramChunks: ReadableArray): List<Chunk> {
      val chunks = mutableListOf<Chunk>();
      for (i in 0 until paramChunks.size()) {
        val paramChunk = paramChunks.getMap(i);
        val position = paramChunk.getDouble("position").toLong()
        val size = paramChunk.getDouble("size").toLong()
        val path = paramChunk.getString("path") ?: throw Throwable("Path is not defined")

        if (size <= 0) throw Throwable("Size is smaller than or equal 0")
        if (position < 0) throw Throwable("Position is smaller than 0")

        chunks.add(Chunk(position, size, path))
      }

      return chunks
    }
  }
}

suspend fun chunkFile(
  scope: CoroutineScope,
  parentFilePath: String,
  chunks: List<Chunk>
) {
  val parentFile = scope.async(Dispatchers.IO) { RandomAccessFile(parentFilePath, "r") }.await()

  chunks.map {
    scope.async(Dispatchers.IO) {
      val outputFile = RandomAccessFile(it.path, "rw")
      val input = parentFile.channel.map(FileChannel.MapMode.READ_ONLY, it.position, it.size)
      val output = outputFile.channel.map(FileChannel.MapMode.READ_WRITE, 0, it.size)
      output.put(input)
    }
  }.awaitAll()
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