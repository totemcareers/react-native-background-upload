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
import com.facebook.react.BuildConfig
import com.facebook.react.bridge.*
import com.vydia.RNUploader.Upload.Companion.defaultNotificationChannel
import com.vydia.RNUploader.Upload.Companion.uploads
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig.initialize
import net.gotev.uploadservice.UploadServiceConfig.httpStack
import net.gotev.uploadservice.UploadServiceConfig.retryPolicy
import net.gotev.uploadservice.UploadServiceConfig.threadPool
import net.gotev.uploadservice.data.RetryPolicyConfig
import net.gotev.uploadservice.observer.request.GlobalRequestObserver
import net.gotev.uploadservice.okhttp.OkHttpStack
import net.gotev.uploadservice.protocols.binary.BinaryUploadRequest
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest
import okhttp3.OkHttpClient
import java.util.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val TAG = "UploaderBridge"
  private val uploadEventListener = GlobalRequestObserverDelegate(reactContext)
  private val connectivityManager =
    reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  override fun getName(): String {
    return "RNFileUploader"
  }


  init {
    // Initialize everything here so listeners can continue to listen
    // seamlessly after JS reloads

    // == limit number of concurrent uploads ==
    val pool = threadPool as ThreadPoolExecutor
    pool.corePoolSize = 1
    pool.maximumPoolSize = 1

    // == set retry policy ==
    retryPolicy = RetryPolicyConfig(
      initialWaitTimeSeconds = 1,
      maxWaitTimeSeconds = TimeUnit.HOURS.toSeconds(1).toInt(),
      multiplier = 2,
      defaultMaxRetries = 2 // this will be overridden by the `maxRetries` option
    )

    // == set http stack ==
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

    val application = reactContext.applicationContext as Application

    // == initialize UploadService ==
    initialize(application, defaultNotificationChannel, BuildConfig.DEBUG)

    // == register upload listener ==
    GlobalRequestObserver(application, uploadEventListener)

    // == register network listener ==
    connectivityManager.registerDefaultNetworkCallback(object : NetworkCallback() {
      override fun onAvailable(network: Network) {
        handleNetworkConditionsChange()
      }

      override fun onCapabilitiesChanged(
        network: Network, networkCapabilities: NetworkCapabilities
      ) {
        handleNetworkConditionsChange()
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        handleNetworkConditionsChange()
      }

      override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        handleNetworkConditionsChange()
      }
    })
  }

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int, promise: Promise) {
    try {
      promise.resolve(chunkFile(parentFilePath, chunkDirPath, numChunks));
    } catch (error: Throwable) {
      promise.reject(error)
    }
  }

  private fun handleNetworkConditionsChange() {
    val count = uploads.size
    Log.d(TAG, "Processing $count deferred uploads")

    uploads.values.forEach { upload ->
      val networkOk = validateNetwork(upload.discretionary, connectivityManager)
      // If upload in progress, check if network conditions still apply.
      // If network is no longer valid, stop the upload.
      // This is because, for example, if the user prefers Wifi,
      // we don't want it to continue uploading and eating up the user's data plan
      // while they're on a Cellular network
      if (!upload.waitingForNetworkOk && !networkOk) {
        upload.waitingForNetworkOk = true
        upload.requestId?.let {
          // removing the requestId to silence the event reporting
          upload.requestId = null
          UploadService.stopUpload(it.value)
        }
      }

      // If upload is waiting for network ok, check if network conditions still apply
      // if network has become valid, start the upload
      if (upload.waitingForNetworkOk && networkOk) {
        upload.waitingForNetworkOk = false
        _startUpload(upload)
      }
    }
  }


  /*
   * Starts a file upload.
   * Returns a promise with the string ID of the upload.
   */
  @ReactMethod
  fun startUpload(rawOptions: ReadableMap, promise: Promise) {
    try {
      val new = Upload(rawOptions)

      // if there's an existing upload, cancel its current request
      uploads[new.id]?.let { old ->
        old.requestId?.let {
          // removing the requestId to silence the event reporting
          old.requestId = null
          UploadService.stopUpload(it.value)
        }
      }

      if (validateNetwork(new.discretionary, connectivityManager))
        _startUpload(new)
      else
        new.waitingForNetworkOk = true

      uploads[new.id] = new
      promise.resolve(new.id.toString())
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
  private fun _startUpload(upload: Upload): Boolean {
    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(upload.notificationChannel, notificationManager)

    val request = if (upload.requestType == Upload.RequestType.RAW) {
      BinaryUploadRequest(this.reactApplicationContext, upload.url)
        .setFileToUpload(upload.path)
    } else {
      MultipartUploadRequest(this.reactApplicationContext, upload.url)
        .addFileToUpload(upload.path, upload.field)
    }

    upload.notification.let {
      if (it != null) request.setNotificationConfig { _, _ -> it }
    }

    upload.parameters.forEach { (key, value) ->
      request.addParameter(key, value)
    }
    upload.headers.forEach { (key, value) ->
      request.addHeader(key, value)
    }

    val requestId = UUID.randomUUID().toString()
    upload.requestId = UploadServiceId(requestId)

    request
      .setMethod(upload.method)
      .setMaxRetries(upload.maxRetries)
      .setUploadID(requestId)
      .startUpload()

    return true
  }


  /*
   * Cancels file upload
   * Accepts upload ID as a first argument, this upload will be cancelled
   * Event "cancelled" will be fired when upload is cancelled.
   */
  @ReactMethod
  fun cancelUpload(uploadId: String, promise: Promise) {
    try {
      uploads[RNUploadId(uploadId)]?.let { upload ->
        val requestId = upload.requestId
        if (requestId == null)
          uploadEventListener.reportCancelled(upload.id)
        else
          UploadService.stopUpload(requestId.value)
      }
      promise.resolve(true)
    } catch (exc: Throwable) {
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

