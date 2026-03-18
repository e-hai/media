package androidx.media3.demo.transformer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.transformer.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
class CameraMicDemoActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var previewView: SurfaceView
    private var previewSurface: Surface? = null

    @Volatile
    private var transformer: Transformer? = null
    private var audioThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isLoaderReady = AtomicBoolean(false)

    // AssetLoaders references
    private val micLoaderRef = AtomicReference<RawAssetLoader>()
    private val surfaceLoaderRef = AtomicReference<SurfaceAssetLoader>()

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera_mic_demo_activity)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        previewView = findViewById(R.id.preview_view)

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopRecording() }

        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface
                Log.d(TAG, "Surface created")
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurface = null
                Log.d(TAG, "Surface destroyed")
            }
        })
        HardwareEncoderInfo.getSupportedVideoEncoders().forEach {
            Log.d(TAG, "Supported video encoder: ${it}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        cleanupResources()
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startRecording()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording...")

        if (previewSurface == null) {
            Toast.makeText(this, "Preview surface not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "Already recording")
            return
        }

        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Initializing..."
        isLoaderReady.set(false)

        // 使用内部存储作为临时文件
        val tempOutputFile = File(filesDir, "recording_${System.currentTimeMillis()}.mp4")

        // 创建目标文件在外部公共目录
        val externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val galleryDir = File(externalDir, "Camera")
        if (!galleryDir.exists()) {
            galleryDir.mkdirs()
        }
        val targetOutputFile = File(galleryDir, "recording_${System.currentTimeMillis()}.mp4")

        // 1. Setup Transformer
        val newTransformer = Transformer.Builder(this)
            .setMuxerFactory(FrameworkMuxer.Factory())
            .setAssetLoaderFactory(CustomAssetLoaderFactory(this, micLoaderRef, surfaceLoaderRef))
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    runOnUiThread {
                        val durationSec = exportResult.durationMs / 1000.0
                        statusText.text =
                            "✅ Saved: ${tempOutputFile.name} (%.1fs)".format(durationSec)

                        // 复制文件到外部相册
                        copyVideoToGallery(tempOutputFile, targetOutputFile)

                        cleanupAfterExport()
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    Log.e(TAG, "Export error", exportException)
                    runOnUiThread {
                        statusText.text = "❌ Error: ${exportException.message}"
                        Toast.makeText(
                            this@CameraMicDemoActivity,
                            "Export failed: ${exportException.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                        cleanupAfterExport()
                    }
                }
            })
            .build()

        transformer = newTransformer

        // 2. Build Composition
        val cameraItem = EditedMediaItem.Builder(
            MediaItem.fromUri("${SurfaceAssetLoader.MEDIA_ITEM_URI_SCHEME}://video")
        ).build()

        val micItem = EditedMediaItem.Builder(
            MediaItem.fromUri("mic://audio")
        ).build()

        val composition = Composition.Builder(
            listOf(
                EditedMediaItemSequence.withVideoFrom(listOf(cameraItem)),
                EditedMediaItemSequence.withAudioFrom(listOf(micItem)),
            )
        ).build()

        // 3. Start Transformer (this will trigger AssetLoader creation)
        try {
            newTransformer.start(composition, tempOutputFile.absolutePath)
            Log.d(TAG, "Transformer started")
            statusText.text = "Preparing camera..."

            //开启录音
            startAudioCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transformer", e)
            isRecording.set(false)
            runOnUiThread {
                statusText.text = "Failed to start: ${e.message}"
                resetUI()
            }
        }
    }

    // Called from Factory callback
    fun onTransformerSurfaceReady(transformerSurface: Surface) {
        Log.d(TAG, "Transformer surface ready, starting camera...")
        isLoaderReady.set(true)
        runOnUiThread {
            startCamera(transformerSurface)
        }
    }

    private fun startCamera(transformerSurface: Surface) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList.firstOrNull() ?: run {
                Log.e(TAG, "No camera available")
                return
            }

            Log.d(TAG, "Opening camera: $cameraId")

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Camera permission not granted")
                return
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened")

                    cameraDevice = camera
                    createCaptureSession(camera, transformerSurface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    runOnUiThread {
                        statusText.text = "Camera error: $error"
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun createCaptureSession(camera: CameraDevice, transformerSurface: Surface) {
        try {
            val preview = previewSurface
            if (preview == null) {
                Log.e(TAG, "Preview surface is null")
                return
            }

            val targets = listOf(preview, transformerSurface)

            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Capture session configured")
                    captureSession = session

                    try {
                        val request =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(preview)
                                addTarget(transformerSurface)
                            }

                        session.setRepeatingRequest(request.build(), null, cameraHandler)

                        runOnUiThread {
                            statusText.text = "🔴 Recording..."
                        }
                        Log.d(TAG, "Camera streaming started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start capture request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    runOnUiThread {
                        statusText.text = "Camera config failed"
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun startAudioCapture() {
        Log.d(TAG, "Starting audio capture...")

        audioThread = Thread {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_DEFAULT
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Audio permission not granted")
                return@Thread
            }

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return@Thread
            }

            val buffer = ByteArray(4096)
            var timestampUs = 0L

            try {
                audioRecord.startRecording()
                Log.d(TAG, "AudioRecord started")

                while (isRecording.get()) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)

                    if (bytesRead > 0) {
                        val loader = micLoaderRef.get()
                        if (loader != null) {
                            val byteBuffer = ByteBuffer.allocateDirect(bytesRead)
                            byteBuffer.put(buffer, 0, bytesRead)
                            byteBuffer.flip()

//                            Log.d(TAG, "Queuing audio data="+timestampUs)
                            // Check for potential overflow before queuing
                            loader.queueAudioData(byteBuffer, timestampUs, false)
                            timestampUs = System.nanoTime() / 1000
                        }
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "Audio read error: $bytesRead")
                        break
                    }

                    Thread.sleep(10)
                }

                // Signal end of audio stream
                Log.d(TAG, "Signaling end of audio stream")
                micLoaderRef.get()?.queueAudioData(ByteBuffer.allocate(0), 0, true)

            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                    Log.d(TAG, "AudioRecord released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord", e)
                }
            }
        }.apply {
            name = "AudioCaptureThread"
            start()
        }
    }

    private fun stopRecording() {
        Log.d(TAG, "Stopping recording...")

        if (!isRecording.getAndSet(false)) {
            Log.w(TAG, "Not currently recording")
            return
        }

        statusText.text = "Finalizing export..."

        try {
            // 1. Stop camera capture
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null
            Log.d(TAG, "Camera stopped")

            // 2. Signal end of video stream (must be called AFTER loader is ready)
            if (isLoaderReady.get()) {
                surfaceLoaderRef.get()?.signalEndOfInput()
                Log.d(TAG, "Signaled end of video input")
            } else {
                Log.w(TAG, "SurfaceAssetLoader not ready, cannot signal end of input")
            }

            // 3. Audio thread will signal end of audio stream automatically when loop exits
            audioThread?.join(5000) // Wait max 5 seconds
            if (audioThread?.isAlive == true) {
                Log.w(TAG, "Audio thread still alive after timeout")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during stop", e)
        }
    }

    private fun cleanupAfterExport() {
        cleanupResources()
        resetUI()
    }

    private fun cleanupResources() {
        isRecording.set(false)
        isLoaderReady.set(false)

        micLoaderRef.set(null)
        surfaceLoaderRef.set(null)
        transformer = null
    }

    private fun resetUI() {
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun copyVideoToGallery(tempFile: File, targetFile: File) {
        try {
            // 复制文件到目标位置
            tempFile.copyTo(targetFile, overwrite = true)

            // 通知媒体扫描器扫描新文件，使其出现在相册中
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(targetFile)
            sendBroadcast(mediaScanIntent)

            Toast.makeText(
                this,
                "Exported to: ${targetFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()

            Log.d(TAG, "Video copied to gallery: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy video to gallery", e)
            Toast.makeText(
                this,
                "Failed to save to gallery: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val TAG = "CameraMicDemo"
        private const val REQUEST_CODE_PERMISSIONS = 100
    }
}

// ============================================================================
// Custom AssetLoader Factory
// ============================================================================

@OptIn(UnstableApi::class)
class CustomAssetLoaderFactory(
    private val activity: CameraMicDemoActivity,
    private val micLoaderRef: AtomicReference<RawAssetLoader>,
    private val surfaceLoaderRef: AtomicReference<SurfaceAssetLoader>
) : AssetLoader.Factory {

    override fun createAssetLoader(
        editedMediaItem: EditedMediaItem,
        looper: Looper,
        listener: AssetLoader.Listener,
        compositionSettings: AssetLoader.CompositionSettings
    ): AssetLoader {
        val uri = editedMediaItem.mediaItem.localConfiguration?.uri
        val scheme = uri?.scheme

        Log.d(TAG, "Creating AssetLoader for URI: $uri (scheme: $scheme)")

        return when (scheme) {
            SurfaceAssetLoader.MEDIA_ITEM_URI_SCHEME -> {
                createSurfaceAssetLoader(editedMediaItem, looper, listener, compositionSettings)
            }

            "mic" -> {
                createMicAssetLoader(editedMediaItem, listener)
            }

            else -> {
                createDefaultAssetLoader(editedMediaItem, looper, listener, compositionSettings)
            }
        }
    }

    private fun createSurfaceAssetLoader(
        editedMediaItem: EditedMediaItem,
        looper: Looper,
        listener: AssetLoader.Listener,
        compositionSettings: AssetLoader.CompositionSettings
    ): AssetLoader {
        Log.d(TAG, "Creating SurfaceAssetLoader")

        return SurfaceAssetLoader.Factory(object : SurfaceAssetLoader.Callback {
            override fun onSurfaceAssetLoaderCreated(loader: SurfaceAssetLoader) {
                Log.d(TAG, "SurfaceAssetLoader created")
                surfaceLoaderRef.set(loader)

                // Define video format
                val format = Format.Builder()
                    .setWidth(1)
                    .setHeight(5000)
                    .setFrameRate(30f)
                    .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                    .setSampleMimeType(MimeTypes.VIDEO_RAW)
                    .build()

                try {
                    // ✅ ONLY set format - Transformer will call start() internally
                    loader.setContentFormat(format)
                    Log.d(
                        TAG,
                        "SurfaceAssetLoader format set: ${format.width}x${format.height}@${format.frameRate}fps"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set SurfaceAssetLoader format", e)
                }
            }

            override fun onSurfaceReady(surface: Surface, editedMediaItem: EditedMediaItem) {
                Log.d(TAG, "Surface ready for camera input")
                activity.onTransformerSurfaceReady(surface)
            }
        }).createAssetLoader(editedMediaItem, looper, listener, compositionSettings)
    }

    private fun createMicAssetLoader(
        editedMediaItem: EditedMediaItem,
        listener: AssetLoader.Listener
    ): AssetLoader {
        Log.d(TAG, "Creating RawAssetLoader for microphone")

        val format = Format.Builder()
            .setChannelCount(1)
            .setSampleRate(44100)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .build()

        val loader = RawAssetLoader(editedMediaItem, listener, format, null, null)
        micLoaderRef.set(loader)
        return loader
    }

    private fun createDefaultAssetLoader(
        editedMediaItem: EditedMediaItem,
        looper: Looper,
        listener: AssetLoader.Listener,
        compositionSettings: AssetLoader.CompositionSettings
    ): AssetLoader {
        Log.d(TAG, "Creating DefaultAssetLoader")
        val context: Context = activity.applicationContext

        return DefaultAssetLoaderFactory(
            context,
            DefaultDecoderFactory.Builder(context)
                .setShouldConfigureOperatingRate(true)
                .build(),
            Clock.DEFAULT,
            null
        ).createAssetLoader(editedMediaItem, looper, listener, compositionSettings)
    }

    companion object {
        private const val TAG = "AssetLoaderFactory"
    }
}