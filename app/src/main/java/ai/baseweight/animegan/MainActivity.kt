package ai.baseweight.animegan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.nio.ByteOrder


class MainActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var getImageButton: Button
    private lateinit var doFetchButton: Button
    private lateinit var doPredictButton: Button

    private var animeGan : AnimeGan? = null

    private var mainBitmap: Bitmap? = null
    private val API_KEY by lazy { getString(R.string.api_key) }
    private val MODEL_ID by lazy { getString(R.string.model_id) }
    private val API_BASE_URL = "https://stage-api.baseweight.ai/api/models/" // Base URL for the API

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Initialize views
        imageView = findViewById(R.id.imageView)
        getImageButton = findViewById(R.id.getImage)
        doFetchButton = findViewById(R.id.doFetch)
        doPredictButton = findViewById(R.id.doPredict)

        // Set up click listeners
        getImageButton.setOnClickListener {
            onGetImageClick()
        }

        doFetchButton.setOnClickListener {
            onDoFetchClick()
        }

        doPredictButton.setOnClickListener {
            onDoPredictClick()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun onGetImageClick() {
        try 
        {
            val i = Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            startActivityForResult(i, 0)
        } catch (e: Exception) {
            // Handle exception
        }
    }

    private fun onDoFetchClick() {
        var external = this.getExternalFilesDir(null)

        if (external != null) {
            downloadModel(external.absolutePath, MODEL_ID, "downloaded_model.onnx")
        } else {
            Toast.makeText(this, "External storage not available", Toast.LENGTH_SHORT).show()
        }        
    }

    private fun onDoPredictClick() {
        // TODO: Implement prediction logic
        if(mainBitmap == null)
        {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
            return
        }
        if(animeGan == null) {
            animeGan = AnimeGan()
        }

        val byteCount = mainBitmap!!.byteCount
        var inputBuffer : ByteBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        mainBitmap!!.copyPixelsToBuffer(inputBuffer)
        val outputBuffer = ByteBuffer.allocateDirect(512 * 512 * 3 * 4).order(ByteOrder.nativeOrder())
        animeGan!!.doPredict(inputBuffer, outputBuffer, mainBitmap!!.width, mainBitmap!!.height)

        mainBitmap = BitmapFactory.decodeByteArray(outputBuffer.array(), 0, outputBuffer.array().size)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val outputBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        outputBuffer.rewind()
        outputBitmap.copyPixelsFromBuffer(outputBuffer)

        imageView.setImageBitmap(outputBitmap)
        imageView.invalidate()

        Toast.makeText(this, "Making prediction...", Toast.LENGTH_SHORT).show()
    }


    private fun downloadModel(destinationPath: String, modelId: String, filename: String) {
        Toast.makeText(this, "Starting model download...", Toast.LENGTH_SHORT).show()

        // First, get the pre-signed URL
        val urlRequest = Request.Builder()
            .url("${API_BASE_URL}${modelId}/download".toHttpUrlOrNull()!!)
            .header("Authorization", "Bearer $API_KEY")
            .build()

        client.newCall(urlRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {

                runOnUiThread {
                    Toast.makeText(this@MainActivity,
                        "Failed to get download URL: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Log.e("MainActivity", "Error getting download URL: ${response.code}")
                        Toast.makeText(this@MainActivity,
                            "Error getting download URL: ${response.code}",
                            Toast.LENGTH_LONG).show()
                    }
                    return
                }

                try {
                    // Parse the JSON response to get the pre-signed URL
                    val jsonResponse = response.body?.string()
                    val jsonObject = JSONObject(jsonResponse)
                    val downloadUrl = jsonObject.getString("download_url")

                    // Now download from the pre-signed URL
                    val downloadRequest = Request.Builder()
                        .url(downloadUrl)
                        .build()

                    client.newCall(downloadRequest).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread {
                                Log.e("MainActivity", "Download failed: ${e.message}")
                                Toast.makeText(this@MainActivity,
                                    "Download failed: ${e.message}",
                                    Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (!response.isSuccessful) {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity,
                                        "Download error: ${response.code}",
                                        Toast.LENGTH_LONG).show()
                                }
                                return
                            }

                            response.body?.let { body ->
                                try {
                                    val modelFile = File(destinationPath, filename)
                                    modelFile.outputStream().use { fileOutputStream ->
                                        body.byteStream().copyTo(fileOutputStream)
                                    }

                                    // Initialize AnimeGan now that we have the model
                                    animeGan = AnimeGan()
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            "Model downloaded successfully!",
                                            Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity,
                                            "Failed to save model: ${e.message}",
                                            Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    runOnUiThread {
                        Log.e("MainActivity", "Failed to parse response: ${e.message}")
                        Toast.makeText(this@MainActivity,
                            "Failed to parse response: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    override fun onActivityResult(reqCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(reqCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val imageUri = data!!.data
            val imageStream = contentResolver.openInputStream(imageUri!!)
            val exifStream = contentResolver.openInputStream(imageUri)
            val exif = ExifInterface(exifStream!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotMatrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotMatrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotMatrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotMatrix.postRotate(270f)
            }
            val selectedImage = BitmapFactory.decodeStream(imageStream)
            val rotatedBitmap = Bitmap.createBitmap(
                selectedImage, 0, 0,
                selectedImage.width, selectedImage.height,
                rotMatrix, true
            )

            // This is really important
            mainBitmap = rotatedBitmap

            runOnUiThread {
                imageView.setImageBitmap(rotatedBitmap);
            }

        } else {
        }
    }
}