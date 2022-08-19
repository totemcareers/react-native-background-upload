package com.vydia.RNUploader

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.facebook.react.BuildConfig
import com.facebook.react.bridge.*
import com.vydia.RNUploader.Upload.Companion.defaultNotificationChannel
import com.vydia.RNUploader.Upload.Companion.uploads
import kotlinx.coroutines.DelicateCoroutinesApi
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig.initialize
import net.gotev.uploadservice.UploadServiceConfig.retryPolicy
import net.gotev.uploadservice.UploadServiceConfig.threadPool
import net.gotev.uploadservice.data.RetryPolicyConfig
import net.gotev.uploadservice.observer.request.GlobalRequestObserver
import net.gotev.uploadservice.okhttp.OkHttpStack
import java.util.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit


@OptIn(DelicateCoroutinesApi::class)
class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val uploadEventListener = GlobalRequestObserverDelegate(reactContext)
  private val connectivityManager =
    reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  companion object {
    val TAG = "UploaderBridge"
    var httpStack: OkHttpStack? = null
    var discretionaryHttpStack: OkHttpStack? = null
  }

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
      // higher wait time to make time to wait for network change
      // and get checked if the request needs to be cancelled instead of emitting errors
      initialWaitTimeSeconds = 20,
      maxWaitTimeSeconds = TimeUnit.HOURS.toSeconds(1).toInt(),
      multiplier = 2,
      defaultMaxRetries = 2 // this will be overridden by the `maxRetries` option
    )


    val application = reactContext.applicationContext as Application

    // == initialize UploadService ==
    initialize(application, defaultNotificationChannel, BuildConfig.DEBUG)

    // == register upload listener ==
    GlobalRequestObserver(application, uploadEventListener)

    // == register network listener ==
    observeBestNetwork(connectivityManager, false) {
      httpStack = buildHttpStack(it)
      handleNetworkChange(false)
    }

    observeBestNetwork(connectivityManager, true) {
      discretionaryHttpStack = buildHttpStack(it)
      handleNetworkChange(true)
    }
  }

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int, promise: Promise) {
    try {
      promise.resolve(chunkFile(parentFilePath, chunkDirPath, numChunks))
    } catch (error: Throwable) {
      promise.reject(error)
    }
  }

  private fun handleNetworkChange(discretionary: Boolean) {
    val count = uploads.size
    val httpStack = if(discretionary) discretionaryHttpStack else httpStack
    Log.d(TAG, "Processing $count deferred uploads. Discretionary: $discretionary")
    Log.d(TAG, "HttpStack available? ${httpStack != null}")

    uploads.values
      .filter { it.discretionary == discretionary }
      .forEach {
        // stop the upload because we're switching network
        // setting requestId to null to prevent cancellation event reporting
        it.requestId = null
        UploadService.stopUpload(it.id.value)
        maybeStartUpload(it)
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

      maybeStartUpload(new)

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
  private fun maybeStartUpload(upload: Upload) {
    if (upload.discretionary && discretionaryHttpStack == null) return
    if (!upload.discretionary && httpStack == null) return

    val notificationManager =
      (reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    initializeNotificationChannel(upload.notificationChannel, notificationManager)

    val requestId = UUID.randomUUID().toString()

    val request = if (upload.requestType == Upload.RequestType.RAW) {
      UploadRequestBinary(reactContext, upload.url, upload.discretionary).apply {
        setFileToUpload(upload.path)
      }
    } else {
      UploadRequestMultipart(reactContext, upload.url, upload.discretionary).apply {
        addFileToUpload(upload.path, upload.field)
        upload.parameters.forEach { (key, value) -> addParameter(key, value) }
      }
    }

    request.apply {
      setMethod(upload.method)
      setMaxRetries(upload.maxRetries)
      setUploadID(requestId)
      upload.notification.let { if (it != null) setNotificationConfig { _, _ -> it } }
      upload.headers.forEach { (key, value) -> addHeader(key, value) }
      startUpload()
    }

    upload.requestId = UploadServiceId(requestId)
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

