package com.example.yalaface

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.objdetect.CascadeClassifier
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import org.opencv.core.Size
import android.graphics.Matrix
import android.media.ExifInterface



class MainActivity : AppCompatActivity() {
    private lateinit var btnCapture: Button
    private lateinit var imageView: ImageView
    private lateinit var imageCapture: ImageCapture
    private var faceCascade: CascadeClassifier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCapture = findViewById(R.id.btncamera_id)
        imageView = findViewById(R.id.imageview1)

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "OpenCV initialization succeeded", Toast.LENGTH_SHORT).show()
        }

        // Load Haar Cascade for face detection
        loadFaceDetectionModel()

        // Request camera permissions or start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        // Handle capture button click
        btnCapture.setOnClickListener {
            takePhoto()
        }

    }

    private fun loadFaceDetectionModel() {
        val inputStream = assets.open("haarcascade_frontalface_default.xml")
        val cascadeDir = getDir("cascade", MODE_PRIVATE)
        val cascadeFile = File(cascadeDir, "haarcascade_frontalface_default.xml")

        cascadeFile.outputStream().use { outputStream ->
            inputStream.use { input ->
                input.copyTo(outputStream)
            }
        }

        faceCascade = CascadeClassifier(cascadeFile.absolutePath)
        if (faceCascade?.empty() == true) {
            faceCascade = null
            Toast.makeText(this, "Failed to load face detection model", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Face detection model loaded successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val photoFile = File(getOutputDirectory(), "${getFileName()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Error saving image: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                    lifecycleScope.launch {
                        // Pass photoFile.absolutePath as filePath to the processImage function
                        processImage(bitmap, photoFile.absolutePath)
                    }
                }
            }
        )
    }

    fun rotateImageIfNeeded(bitmap: Bitmap, filePath: String): Bitmap {
        val exif = ExifInterface(filePath)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        // Rotate the image if necessary
        if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        return bitmap
    }

    private suspend fun processImage(bitmap: Bitmap, filePath: String) {
        try {
            // Rotate the image if needed
            val rotatedBitmap = rotateImageIfNeeded(bitmap, filePath)

            // Create a mutable copy of the rotated bitmap
            val mutableBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)

            val downscaledBitmap = Bitmap.createScaledBitmap(mutableBitmap, 640, 864, true)

            val cascadeFile = copyCascadeFile("haarcascade_frontalface_default.xml")
            val faceCascade = CascadeClassifier(cascadeFile.absolutePath)

            if (faceCascade.empty()) {
                throw IOException("Failed to load cascade file")
            }

            val mat = Mat()
            Utils.bitmapToMat(downscaledBitmap, mat)

            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

            val faces = MatOfRect()
            faceCascade.detectMultiScale(
                grayMat,
                faces,
                1.2, // Lower scale factor for better detection of smaller faces
                2,   // Reduce minNeighbors for less strict detection
                0,   // Flags
                Size(200.0, 200.0), // Allow smaller face sizes
                Size() // Max size (leave empty if no limit)
            )


            if (faces.toArray().isNotEmpty()) {
                val faceRect = faces.toArray()[0]
                val faceX = faceRect.x
                val faceY = faceRect.y
                val faceWidth = faceRect.width
                val faceHeight = faceRect.height

                // Draw rectangle around detected face
                val canvas = Canvas(downscaledBitmap)
                val paint = Paint()
                paint.color = Color.RED
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f
                canvas.drawRect(
                    faceX.toFloat(),
                    faceY.toFloat(),
                    (faceX + faceWidth).toFloat() ,
                    (faceY + faceHeight).toFloat() ,
                    paint
                )
                // Save image with rectangle
                saveBitmap(downscaledBitmap, "detected_face")

                // Crop face to square
                val faceSize = minOf(faceWidth, faceHeight)
                val offset = (faceWidth - faceSize) / 2
                val faceBitmap = Bitmap.createBitmap(downscaledBitmap, faceX + offset, faceY, faceSize, faceSize)

                // Resize face to 160x160
                val resizedFaceBitmap = Bitmap.createScaledBitmap(faceBitmap, 160, 160, true)

                // Save cropped face
                saveBitmap(resizedFaceBitmap, "cropped_face")

                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(resizedFaceBitmap)
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No face detected", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Error processing image", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun saveBitmap(bitmap: Bitmap, filename: String) {
        val file = File(getOutputDirectory(), "$filename.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
    }

    private fun copyCascadeFile(fileName: String): File {
        val cascadeDir = File(filesDir, "cascade")
        if (!cascadeDir.exists()) {
            cascadeDir.mkdirs()
        }
        val cascadeFile = File(cascadeDir, fileName)
        if (!cascadeFile.exists()) {
            assets.open(fileName).use { inputStream ->
                cascadeFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return cascadeFile
    }


    private fun saveBitmap(bitmap: Bitmap) {
        val faceFile = File(getOutputDirectory(), "face_${getFileName()}.jpg")
        faceFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return mediaDir ?: filesDir
    }

    private fun getFileName(): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "JPEG_$timeStamp"
    }

    private val requestPermissions = registerForActivityResult(RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}