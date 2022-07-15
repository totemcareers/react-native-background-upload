package com.vydia.RNUploader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo.DetailedState
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import com.facebook.react.BuildConfig
import com.facebook.react.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig.httpStack
import net.gotev.uploadservice.UploadServiceConfig.initialize
import net.gotev.uploadservice.data.UploadInfo
import net.gotev.uploadservice.data.UploadNotificationConfig
import net.gotev.uploadservice.data.UploadNotificationStatusConfig
import net.gotev.uploadservice.exceptions.UserCancelledUploadException
import net.gotev.uploadservice.observer.request.GlobalRequestObserver
import net.gotev.uploadservice.okhttp.OkHttpStack
import net.gotev.uploadservice.protocols.binary.BinaryUploadRequest
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest
import okhttp3.OkHttpClient
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.TimeUnit

class DeferredUpload(
  val id: String,
  val options: ReadableMap,
) {}

class UploaderModule(val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val TAG = "UploaderBridge"
  private var notificationChannelID = "BackgroundUploadChannel"
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
    initializeNotificationChannel()

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

  private fun configureUploadServiceHTTPStack(options: ReadableMap) {
    var followRedirects = true
    var followSslRedirects = true
    var retryOnConnectionFailure = true
    var connectTimeout = 15
    var writeTimeout = 30
    var readTimeout = 30
    //TODO: make 'cache' customizable
    if (options.hasKey("followRedirects")) {
      if (options.getType("followRedirects") != ReadableType.Boolean) {
        throw InvalidUploadOptionException("followRedirects must be a boolean.")
      }
      followRedirects = options.getBoolean("followRedirects")
    }
    if (options.hasKey("followSslRedirects")) {
      if (options.getType("followSslRedirects") != ReadableType.Boolean) {
        throw InvalidUploadOptionException("followSslRedirects must be a boolean.")
      }
      followSslRedirects = options.getBoolean("followSslRedirects")
    }
    if (options.hasKey("retryOnConnectionFailure")) {
      if (options.getType("retryOnConnectionFailure") != ReadableType.Boolean) {
        throw InvalidUploadOptionException("retryOnConnectionFailure must be a boolean.")
      }
      retryOnConnectionFailure = options.getBoolean("retryOnConnectionFailure")
    }
    if (options.hasKey("connectTimeout")) {
      if (options.getType("connectTimeout") != ReadableType.Number) {
        throw InvalidUploadOptionException("connectTimeout must be a number.")
      }
      connectTimeout = options.getInt("connectTimeout")
    }
    if (options.hasKey("writeTimeout")) {
      if (options.getType("writeTimeout") != ReadableType.Number) {
        throw InvalidUploadOptionException("writeTimeout must be a number.")
      }
      writeTimeout = options.getInt("writeTimeout")
    }
    if (options.hasKey("readTimeout")) {
      if (options.getType("readTimeout") != ReadableType.Number) {
        throw InvalidUploadOptionException("readTimeout must be a number.")
      }
      readTimeout = options.getInt("readTimeout")
    }
    httpStack = OkHttpStack(
      OkHttpClient().newBuilder()
        .followRedirects(followRedirects)
        .followSslRedirects(followSslRedirects)
        .retryOnConnectionFailure(retryOnConnectionFailure)
        .connectTimeout(connectTimeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(writeTimeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(readTimeout.toLong(), TimeUnit.SECONDS)
        .cache(null)
        .build()
    )
  }

  @ReactMethod
  fun chunkFile(parentFilePath: String, chunkDirPath: String, numChunks: Int, promise: Promise) {
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

    promise.resolve(chunkRanges);
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
  fun startUpload(options: ReadableMap, promise: Promise) {
    try {
      var uploadId: String? = null
      if (options.hasKey("customUploadId"))
        uploadId = options.getString("customUploadId")
      if (uploadId == null)
        uploadId = UUID.randomUUID().toString()

      val started = _startUpload(uploadId, options)
      if (!started) deferredUploads.add(DeferredUpload(uploadId, options))
      promise.resolve(uploadId)
    } catch (exc: java.lang.Exception) {
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
  private fun _startUpload(uploadId: String, options: ReadableMap): Boolean {
    for (key in arrayOf("url", "path")) {
      if (!options.hasKey(key)) {
        throw InvalidUploadOptionException("Missing '$key' field.")
      }
      if (options.getType(key) != ReadableType.String) {
        throw InvalidUploadOptionException("$key must be a string.")
      }
    }
    if (options.hasKey("headers") && options.getType("headers") != ReadableType.Map) {
      throw InvalidUploadOptionException("headers must be a hash.")
    }
    if (options.hasKey("notification") && options.getType("notification") != ReadableType.Map) {
      throw InvalidUploadOptionException("notification must be a hash.")
    }

    configureUploadServiceHTTPStack(options)

    var requestType: String? = "raw"
    if (options.hasKey("type")) {
      requestType = options.getString("type")
      if (requestType == null) {
        throw InvalidUploadOptionException("type must be string.")
      }
      if (requestType != "raw" && requestType != "multipart") {
        throw InvalidUploadOptionException("type should be string: raw or multipart.")
      }
    }
    val notification: WritableMap = WritableNativeMap()
    notification.putBoolean("enabled", true)
    if (options.hasKey("notification")) {
      notification.merge(options.getMap("notification")!!)
    }

    if (notification.hasKey("notificationChannel")) {
      notificationChannelID = notification.getString("notificationChannel")!!
      initializeNotificationChannel()
    }

    var discretionary = false
    if (options.hasKey("isDiscretionary")) {
      discretionary = options.getBoolean("isDiscretionary")
    }

    if(uploadShouldBeDeferred(discretionary))
      return false

    val url = options.getString("url")
    val filePath = options.getString("path")
    val method =
      if (options.hasKey("method") && options.getType("method") == ReadableType.String) options.getString(
        "method"
      ) else "POST"
    val maxRetries =
      if (options.hasKey("maxRetries") && options.getType("maxRetries") == ReadableType.Number) options.getInt(
        "maxRetries"
      ) else 2


    val request = if (requestType == "raw") {
      BinaryUploadRequest(this.reactApplicationContext, url!!)
        .setFileToUpload(filePath!!)
    } else {
      if (!options.hasKey("field")) {
        throw InvalidUploadOptionException("field is required field for multipart type.")
      }
      if (options.getType("field") != ReadableType.String) {
        throw InvalidUploadOptionException("field must be string.")
      }
      MultipartUploadRequest(this.reactApplicationContext, url!!)
        .addFileToUpload(filePath!!, options.getString("field")!!)
    }
    request.setMethod(method!!)
      .setMaxRetries(maxRetries)
    if (notification.getBoolean("enabled")) {
      val notificationConfig = UploadNotificationConfig(
        notificationChannelId = notificationChannelID,
        isRingToneEnabled = notification.hasKey("enableRingTone") && notification.getBoolean("enableRingTone"),
        progress = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onProgressTitle")) notification.getString("onProgressTitle")!! else "",
          message = if (notification.hasKey("onProgressMessage")) notification.getString("onProgressMessage")!! else ""
        ),
        success = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onCompleteTitle")) notification.getString("onCompleteTitle")!! else "",
          message = if (notification.hasKey("onCompleteMessage")) notification.getString("onCompleteMessage")!! else "",
          autoClear = notification.hasKey("autoClear") && notification.getBoolean("autoClear")
        ),
        error = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onErrorTitle")) notification.getString("onErrorTitle")!! else "",
          message = if (notification.hasKey("onErrorMessage")) notification.getString("onErrorMessage")!! else ""
        ),
        cancelled = UploadNotificationStatusConfig(
          title = if (notification.hasKey("onCancelledTitle")) notification.getString("onCancelledTitle")!! else "",
          message = if (notification.hasKey("onCancelledMessage")) notification.getString("onCancelledMessage")!! else ""
        )
      )
      request.setNotificationConfig { _, _ ->
        notificationConfig
      }
    }
    if (options.hasKey("parameters")) {
      if (requestType == "raw") {
        throw InvalidUploadOptionException("Parameters supported only in multipart type")
      }
      val parameters = options.getMap("parameters")
      val keys = parameters!!.keySetIterator()
      while (keys.hasNextKey()) {
        val key = keys.nextKey()
        if (parameters.getType(key) != ReadableType.String) {
          throw InvalidUploadOptionException("Parameters must be string key/values. Value was invalid for '$key'")
        }
        request.addParameter(key, parameters.getString(key)!!)
      }
    }
    if (options.hasKey("headers")) {
      val headers = options.getMap("headers")
      val keys = headers!!.keySetIterator()
      while (keys.hasNextKey()) {
        val key = keys.nextKey()
        if (headers.getType(key) != ReadableType.String) {
          throw InvalidUploadOptionException("Headers must be string key/values.  Value was invalid for '$key'")
        }
        request.addHeader(key, headers.getString(key)!!)
      }
    }

    request
      .setUploadID(uploadId)
      .startUpload()

    return true
  }

  /*
   * Validate network connectivity
   * Inspired by react-native-community/netinfo
   */
  private fun uploadShouldBeDeferred(discretionary: Boolean): Boolean {
    val connectivityManager =
      reactContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true

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
      isInternetSuspended = networkInfo.detailedState != DetailedState.CONNECTED
    }

    if (isInternetSuspended) return true
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true

    return false
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

  // Customize the notification channel as you wish. This is only for a bare minimum example
  private fun initializeNotificationChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val channel = NotificationChannel(
        notificationChannelID,
        "Background Upload Channel",
        NotificationManager.IMPORTANCE_LOW
      )
      val manager =
        reactApplicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }

    val application = reactContext.applicationContext as Application
    initialize(application, notificationChannelID, BuildConfig.DEBUG)
  }
}

class InvalidUploadOptionException(message: String) : IllegalArgumentException(message) {}