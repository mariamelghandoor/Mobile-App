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
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalTime

class MainActivity : AppCompatActivity() {

    // widgets in screen
    private lateinit var btnCapture: Button
    private lateinit var imageView: ImageView
    private lateinit var predictedLabelTextView: TextView
    // global variables to use
    private lateinit var imageCapture: ImageCapture
    private var faceCascade: CascadeClassifier? = null
    private lateinit var excelFile: File


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // ensure uniqe id for each session
        val uniqueId = UUID.randomUUID().toString()
        val fileName = "predictions_$uniqueId.xlsx"
        // initialize the widgets
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

        // initialize my excel sheet
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
        // load the file and copying it to work with the copy
        val inputStream = assets.open("haarcascade_frontalface_default.xml")
        val cascadeDir = getDir("cascade", MODE_PRIVATE)
        val cascadeFile = File(cascadeDir, "haarcascade_frontalface_default.xml")

        cascadeFile.outputStream().use { outputStream ->
            inputStream.use { input ->
                input.copyTo(outputStream)
            }
        }

        // make sure my copy is not empty
        faceCascade = CascadeClassifier(cascadeFile.absolutePath)
        if (faceCascade?.empty() == true) {
            faceCascade = null
            Toast.makeText(this, "Failed to load face detection model", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Face detection model loaded successfully", Toast.LENGTH_SHORT).show()
        }
    }
    // loading my labels in list from my txt file
    private fun loadClassLabels(): List<String> {
        val classLabels = mutableListOf<String>()
        try {
            val inputStream = assets.open("class_labels.txt")
            val bufferedReader = InputStreamReader(inputStream).buffered()
            bufferedReader.forEachLine {
                classLabels.add(it.trim())
            }
            bufferedReader.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return classLabels
    }


    private fun startCamera() {
        // interaction with camerax
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        // to display when the camera is done
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            // make what the camera see i see too live
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            // choose back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                //This ensures that the camera starts when the activity is created
                //and is released when the activity is destroyed.
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
        // use the instance i made up
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
                        processImage(bitmap, photoFile.absolutePath)
                    }
                }
            }
        )
    }
    // fix that the phone takes imgs reotated
    fun rotateImageIfNeeded(bitmap: Bitmap, filePath: String): Bitmap {
        val exif = ExifInterface(filePath)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        // Rotate the image
        if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        return bitmap
    }

    // prediction , preproccessing, and displaying
    private suspend fun processImage(bitmap: Bitmap, filePath: String) {
        try {
            // load the txt file
            val classLabels = loadClassLabels()
            // Rotate the image
            val rotatedBitmap = rotateImageIfNeeded(bitmap, filePath)

            // Create a mutable copy of the rotated bitmap
            val mutableBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)

            // make it smaller so when cropping it wont get messed up
            val downscaledBitmap = Bitmap.createScaledBitmap(mutableBitmap, 640, 864, true)

            // load detection file of opencv
            val cascadeFile = copyCascadeFile("haarcascade_frontalface_default.xml")
            val faceCascade = CascadeClassifier(cascadeFile.absolutePath)

            if (faceCascade.empty()) {
                throw IOException("Failed to load cascade file")
            }


            // make gray for less complex detection
            val mat = Mat()
            Utils.bitmapToMat(downscaledBitmap, mat)

            val grayMat = Mat()
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

            // specify the face aspects
            val faces = MatOfRect()
            faceCascade.detectMultiScale(
                grayMat,
                faces,
                1.2, // Lower scale factor for better detection of smaller faces
                2,   // Reduce minNeighbors for less strict detection
                0,
                Size(100.0, 100.0), // Allow smaller face sizes
                Size() // Max size
            )

            // loop on the faces if exists
            val detectedFaces = faces.toArray()
            val detectedLabels = mutableListOf<String>()
            if (detectedFaces.isNotEmpty()) {
                val canvas = Canvas(downscaledBitmap)
                val paint = Paint()
                paint.color = Color.RED
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 10f

                for ((index, faceRect) in detectedFaces.withIndex()) {
                    val faceX = faceRect.x
                    val faceY = faceRect.y
                    val faceWidth = faceRect.width
                    val faceHeight = faceRect.height

                    // Draw rectangle around detected face
                    canvas.drawRect(
                        faceX.toFloat(),
                        faceY.toFloat(),
                        (faceX + faceWidth).toFloat(),
                        (faceY + faceHeight).toFloat(),
                        paint
                    )

                    // Crop face to square
                    val faceSize = minOf(faceWidth, faceHeight)
                    val offset = (faceWidth - faceSize) / 2
                    val faceBitmap = Bitmap.createBitmap(downscaledBitmap, faceX + offset, faceY, faceSize, faceSize)

                    // Resize face to 160x160
                    val resizedFaceBitmap = Bitmap.createScaledBitmap(faceBitmap, 160, 160, true)

                    // Save cropped face
                    saveBitmap(resizedFaceBitmap, "cropped_face_$index")

                    // Predict the face
                    val prediction = predictFaceClass(resizedFaceBitmap)
                    val predictedLabel = classLabels[prediction]
                    detectedLabels.add("Face $index: $predictedLabel")

                    // Save predictions to Excel
                    savePredictionsToExcel(predictedLabel, excelFile)
                }

                if (detectedLabels.isNotEmpty()) {
                    val predictions = buildString {
                        append("Predictions (${detectedLabels.size}):\n")
                        detectedLabels.forEachIndexed { index, label ->
                            append("${index + 1}: $label\n")
                        }
                    }
                    updatePredictedLabel(predictions)
                } else {
                    updatePredictedLabel("No faces detected")
                }


                // Save image with rectangles
                saveBitmap(downscaledBitmap, "detected_faces")

            } else {
                // if no face exists
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
        // Load TFLite model
        val classLabels = loadClassLabels()
        val model = Interpreter(loadModelFile("model.tflite"))

        // Preprocess the image
        val input = preprocessBitmap(bitmap)

        // Prepare output buffer
        val output = Array(1) { FloatArray(classLabels.size) } // number of classes

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

    // helps in loading models from assets
    private fun loadModelFile(fileName: String): ByteBuffer {
        val assetFileDescriptor = assets.openFd(fileName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // displayed text to predicted people
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

    // save to excel wil the file name specified up
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

            // Get current date and time
            val currentDate = LocalDate.now().toString()
            val currentTime = LocalTime.now().toString()

            // Create cells for the predicted label, date, and time
            row.createCell(0).setCellValue(predictedLabel) // Column A: Predicted Label
            row.createCell(1).setCellValue(currentDate)    // Column B: Date
            row.createCell(2).setCellValue(currentTime)    // Column C: Time

            // Write the workbook to a file
            val fileOut = FileOutputStream(excelFile)
            workbook.write(fileOut)
            fileOut.close()

            // Show a toast to indicate the file has been saved
            Toast.makeText(this, "Prediction saved", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error saving prediction to Excel: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }



    // save imgs to storage
    private fun saveBitmap(bitmap: Bitmap, filename: String) {
        val file = File(getOutputDirectory(), "$filename.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
    }

    // copy for write access
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