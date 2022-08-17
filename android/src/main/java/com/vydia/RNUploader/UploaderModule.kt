package com.vydia.RNUploader

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.MimeTypeMap
import com.facebook.react.bridge.*
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig.httpStack
import net.gotev.uploadservice.UploadServiceConfig.retryPolicy
import net.gotev.uploadservice.UploadServiceConfig.threadPool
import net.gotev.uploadservice.data.RetryPolicyConfig
import net.gotev.uploadservice.data.UploadInfo
import net.gotev.uploadservice.exceptions.UserCancelledUploadException
import net.gotev.uploadservice.observer.request.GlobalRequestObserver
import net.gotev.uploadservice.okhttp.OkHttpStack
import net.gotev.uploadservice.protocols.binary.BinaryUploadRequest
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest
import okhttp3.OkHttpClient
import java.io.File
import java.util.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class DeferredUpload(val id: String, val options: StartUploadOptions)

class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val TAG = "UploaderBridge"
  private val uploadEventListener = GlobalRequestObserverDelegate(reactContext)

  override fun getName(): String {
    return "RNFileUploader"
  }

  // Store data in static variables in case JS reloads
  companion object {
    val deferredUploads = mutableListOf<DeferredUpload>()
  }

  init {
    // Initialize everything here so listeners can continue to listen
    // seamlessly after JS reloads

    // == limit number of concurrent uploads ==
    val pool = threadPool as ThreadPoolExecutor
    pool.corePoolSize = 1
    pool.maximumPoolSize = 1

    retryPolicy = RetryPolicyConfig(
      initialWaitTimeSeconds = 1,
      maxWaitTimeSeconds = TimeUnit.HOURS.toSeconds(1).toInt(),
      multiplier = 2,
      defaultMaxRetries = 2 // this will be overridden by the `maxRetries` option
    )

    httpStack = OkHttpStack(
      OkHttpClient().newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15L, TimeUnit.SECONDS)
        .writeTimeout(30L, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .cache(null)
        .build()
    )

    // == register upload listener ==
    val application = reactContext.applicationContext as Application
    GlobalRequestObserver(application, uploadEventListener)

    // == register network listener ==
    val connectivityManager =
      reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    connectivityManager.registerDefaultNetworkCallback(object : NetworkCallback() {
      override fun onAvailable(network: Network) {
        processDeferredUploads()
      }

      override fun onCapabilitiesChanged(
        network: Network, networkCapabilities: NetworkCapabilities
      ) {
        processDeferredUploads()
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        processDeferredUploads()
      }

      override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        processDeferredUploads()
      }
    })
  }

  /*
  Gets file information for the path specified.  Example valid path is: /storage/extSdCard/DCIM/Camera/20161116_074726.mp4
  Returns an object such as: {extension: "mp4", size: "3804316", exists: true, mimeType: "video/mp4", name: "20161116_074726.mp4"}
   */
  @ReactMethod
  fun getFileInfo(path: String, promise: Promise) {
    try {
      val params = Arguments.createMap()
      val fileInfo = File(path)
      params.putString("name", fileInfo.name)
      if (!fileInfo.exists() || !fileInfo.isFile) {
        params.putBoolean("exists", false)
      } else {
        params.putBoolean("exists", true)
        params.putString(
          "size",
          fileInfo.length().toString()
        ) //use string form of long because there is no putLong and converting to int results in a max size of 17.2 gb, which could happen.  Javascript will need to convert it to a number
        val extension = MimeTypeMap.getFileExtensionFromUrl(path)
        params.putString("extension", extension)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        params.putString("mimeType", mimeType)
      }
      promise.resolve(params)
    } catch (exc: Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int, promise: Promise) {
    try {
      promise.resolve(chunkFile(parentFilePath, chunkDirPath, numChunks));
    } catch (error: Throwable) {
      promise.reject(error)
    }
  }

  private fun processDeferredUploads() {
    val uploads = deferredUploads.size
    Log.d(TAG, "Processing $uploads deferred uploads")

    val startedUploads = mutableListOf<DeferredUpload>()
    deferredUploads.forEach {
      if (_startUpload(it.id, it.options))
        startedUploads.add(it)
    }
    deferredUploads.removeAll(startedUploads)
  }


  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  fun startUpload(rawOptions: ReadableMap, promise: Promise) {
    try {
      val uploadId = rawOptions.getString("customUploadId") ?: UUID.randomUUID().toString()
      val options = StartUploadOptions(rawOptions)
      val started = _startUpload(uploadId, options)
      if (!started) deferredUploads.add(DeferredUpload(uploadId, options))
      promise.resolve(uploadId)
    } catch (exc: Throwable) {
      if (exc !is InvalidUploadOptionException) {
        exc.printStackTrace()
        Log.e(TAG, exc.message, exc)
      }
      promise.reject(exc)
    }
  }

  /**
   * @return whether the upload was started
   */
  private fun _startUpload(uploadId: String, options: StartUploadOptions): Boolean {
    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(options.notificationChannel, notificationManager)

    val request = if (options.requestType == StartUploadOptions.RequestType.RAW) {
      BinaryUploadRequest(this.reactApplicationContext, options.url)
        .setFileToUpload(options.path)
    } else {
      MultipartUploadRequest(this.reactApplicationContext, options.url)
        .addFileToUpload(options.path, options.field)
    }

    val notification = options.notification
    if (notification != null)
      request.setNotificationConfig { _, _ -> notification }

    val connectivityManager =
      reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    if (!validateNetwork(options.discretionary, connectivityManager))
      return false


    options.parameters.forEach { (key, value) ->
      request.addParameter(key, value)
    }
    options.headers.forEach { (key, value) ->
      request.addHeader(key, value)
    }

    request
      .setMethod(options.method)
      .setMaxRetries(options.maxRetries)
      .setUploadID(uploadId)
      .startUpload()

    return true
  }


  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(cancelUploadId: String?, promise: Promise) {
    if (cancelUploadId !is String) {
      promise.reject(InvalidUploadOptionException("Upload ID must be a string"))
      return
    }

    // look in the deferredUploads list first
    if (deferredUploads.removeIf { it.id == cancelUploadId }) {
      promise.resolve(true)
      // report error for consistency sake
      uploadEventListener.onError(
        reactContext,
        UploadInfo(cancelUploadId),
        UserCancelledUploadException()
      )
      return
    }

    // if it's not in the deferredUploads, it must have been started,
    // so we call stopUpload()
    try {
      UploadService.stopUpload(cancelUploadId)
      promise.resolve(true)
    } catch (exc: java.lang.Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }

  /*
   * Cancels all file uploads
   */
  @ReactMethod
  fun stopAllUploads(promise: Promise) {
    try {
      UploadService.stopAllUploads()
      promise.resolve(true)
    } catch (exc: java.lang.Exception) {
      exc.printStackTrace()
      Log.e(TAG, exc.message, exc)
      promise.reject(exc)
    }
  }


}

