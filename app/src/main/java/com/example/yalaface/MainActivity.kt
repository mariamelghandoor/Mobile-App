package com.example.yalaface

import android.view.View
import android.Manifest
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
import android.os.Handler
import android.os.Looper
import android.media.ExifInterface
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.FileInputStream
import java.nio.channels.FileChannel
import android.widget.TextView
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var btnCapture: Button
    private lateinit var imageView: ImageView
    private lateinit var imageCapture: ImageCapture
    private var faceCascade: CascadeClassifier? = null
    private lateinit var predictedLabelTextView: TextView
    private lateinit var excelFile: File


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uniqueId = UUID.randomUUID().toString()
        val fileName = "predictions_$uniqueId.xlsx"

        btnCapture = findViewById(R.id.btncamera_id)
        imageView = findViewById(R.id.imageview1)
        predictedLabelTextView = findViewById(R.id.predictedLabelTextView)

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

        excelFile = File(applicationContext.getExternalFilesDir(null), fileName)
        if (!excelFile.exists()) {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Predictions")
            val fileOut = FileOutputStream(excelFile)
            workbook.write(fileOut)
            fileOut.close()
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

                val prediction = predictFaceClass(resizedFaceBitmap)
                val predictedLabel = class_labels[prediction]
                updatePredictedLabel(predictedLabel)
                withContext(Dispatchers.Main) {
//                    imageView.setImageBitmap(resizedFaceBitmap)
                    Toast.makeText(this@MainActivity, "Detected: $predictedLabel", Toast.LENGTH_SHORT).show()
                }
                savePredictionsToExcel(predictedLabel,excelFile)

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
    private fun predictFaceClass(bitmap: Bitmap): Int {
        // Load TFLite model (as ByteBuffer)
        val model = Interpreter(loadModelFile("model.tflite"))

        // Preprocess the image
        val input = preprocessBitmap(bitmap)

        // Prepare output buffer
        val output = Array(1) { FloatArray(class_labels.size) }

        // Run inference
        model.run(input, output)

        // Get the index of the max value (predicted class)
        return output[0].indices.maxByOrNull { output[0][it] } ?: -1
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val inputSize = 160
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        buffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF) / 255.0f
            val g = (pixel shr 8 and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }

        return buffer
    }
    private fun loadModelFile(fileName: String): ByteBuffer {
        val assetFileDescriptor = assets.openFd(fileName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private val class_labels = listOf(
        "Akshay Kumar", "Alexandra Daddario", "Alia Bhatt", "Amitabh Bachchan", "Anas Mahmoud",
        "Andy Samberg", "Anushka Sharma", "Billie Eilish", "Brad Pitt", "Camila Cabello",
        "Charlize Theron", "Claire Holt", "Courtney Cox", "Dwayne Johnson", "Elizabeth Olsen",
        "Ellen Degeneres", "Henry Cavill", "Hrithik Roshan", "Hugh Jackman", "Jessica Alba",
        "Kashyap", "Lisa Kudrow", "Margot Robbie", "Marmik", "Natalie Portman",
        "Priyanka Chopra", "Robert Downey Jr", "Roger Federer", "Tom Cruise", "Vijay Deverakonda",
        "Virat Kohli", "Zac Efron", "ben_afflek", "chris_evans", "chris_hemsworth",
        "elton_john", "jerry_seinfeld", "madonna", "mark_ruffalo", "mindy_kaling",
        "robert_downey_jr", "salma_sherif", "scarlett_johansson"
    )
    private fun updatePredictedLabel(predictedLabel: String) {
        val textView: TextView = findViewById(R.id.predictedLabelTextView)

        // Show the TextView and set the predicted label
        textView.text = "Predicted Class: $predictedLabel"
        textView.visibility = View.VISIBLE

        // Hide the TextView after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            textView.visibility = View.GONE
        }, 3000) // 3000 milliseconds = 3 seconds
    }

    private fun savePredictionsToExcel(predictedLabel: String, excelFile: File) {
        try {
            val workbook: XSSFWorkbook = if (excelFile.exists()) {
                val fis = FileInputStream(excelFile)
                XSSFWorkbook(fis)
            } else {
                XSSFWorkbook() // Create a new workbook if the file does not exist
            }

            val sheet: Sheet = workbook.getSheetAt(0) ?: workbook.createSheet("Predictions")

            // Find the last row to append new data
            val lastRowNum = sheet.physicalNumberOfRows
            val row: Row = sheet.createRow(lastRowNum)

            // Create a cell for the predicted label
            row.createCell(0).setCellValue(predictedLabel)

            // Write the workbook to a file
            val fileOut = FileOutputStream(excelFile)
            workbook.write(fileOut)
            fileOut.close()

            // Show a toast to indicate the file has been saved
            Toast.makeText(this, "Prediction saved to $excelFile", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error saving prediction to Excel: ${e.message}", Toast.LENGTH_SHORT).show()
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