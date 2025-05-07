package ai.baseweight.animegan

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import ai.baseweight.sdk.ModelDownloader;

class AnimeGan(private val modelBuffer: ByteBuffer) {

    val env = OrtEnvironment.getEnvironment()
    lateinit var session : OrtSession
    lateinit var sessionOptions : OrtSession.SessionOptions

    init {                
        try {
            sessionOptions = OrtSession.SessionOptions()
            session = env.createSession(modelBuffer, sessionOptions)
        }
        catch (e: OrtException) {
            Log.d("AnimeGan", "OrtException: " + e.message)
        }
    }

    fun preprocess(inputBuffer: ByteBuffer, outputBuffer: FloatBuffer, width: Int, height: Int) {
        // Create a bitmap from the input buffer
        val inputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        inputBuffer.rewind()
        inputBitmap.copyPixelsFromBuffer(inputBuffer)

        // Resize to 512x512 if needed
        val resizedBitmap = if (inputBitmap.width != 512 || inputBitmap.height != 512) {
            Bitmap.createScaledBitmap(inputBitmap, 512, 512, true)
        } else {
            inputBitmap
        }

        // Convert to RGB and normalize to [-1, 1]
        val pixels = IntArray(512 * 512)
        resizedBitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)
        
        outputBuffer.rewind()
        
        // Debug: Check first few pixel values
        Log.d("AnimeGan", "First few input pixels: ${pixels.take(5).joinToString()}")
        
        // First, write all R values
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 127.5f - 1.0f
            outputBuffer.put(r)
        }
        
        // Then, write all G values
        for (pixel in pixels) {
            val g = ((pixel shr 8) and 0xFF) / 127.5f - 1.0f
            outputBuffer.put(g)
        }
        
        // Finally, write all B values
        for (pixel in pixels) {
            val b = (pixel and 0xFF) / 127.5f - 1.0f
            outputBuffer.put(b)
        }
        
        outputBuffer.rewind()
        
        // Debug: Check first few normalized values
        val debugValues = FloatArray(15) // Get first 5 pixels (3 channels each)
        outputBuffer.get(debugValues)
        Log.d("AnimeGan", "First few normalized values: ${debugValues.joinToString()}")
        outputBuffer.rewind()
        
        // Debug: Check min/max values
        var minValue = Float.MAX_VALUE
        var maxValue = Float.MIN_VALUE
        val allValues = FloatArray(outputBuffer.remaining())
        outputBuffer.get(allValues)
        for (value in allValues) {
            minValue = minOf(minValue, value)
            maxValue = maxOf(maxValue, value)
        }
        Log.d("AnimeGan", "Normalized value range: min=$minValue, max=$maxValue")
        outputBuffer.rewind()
    }

    fun postprocess(inputBuffer: FloatBuffer, outputBuffer: ByteBuffer) {
        inputBuffer.rewind()
        outputBuffer.rewind()
        
        val numPixels = 512 * 512
        
        // Read all R values
        val rValues = FloatArray(numPixels)
        inputBuffer.get(rValues)
        
        // Read all G values
        val gValues = FloatArray(numPixels)
        inputBuffer.get(gValues)
        
        // Read all B values
        val bValues = FloatArray(numPixels)
        inputBuffer.get(bValues)
        
        // Convert back to ARGB8888 format (BGRA, because of endianess)
        for (i in 0 until numPixels) {
            // Denormalize from [-1, 1] to [0, 255]
            val b = ((rValues[i] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
            val g = ((gValues[i] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
            val r = ((bValues[i] + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
            
            // Combine into ARGB8888 format (alpha = 255)
            val argb = (255 shl 24) or (r shl 16) or (g shl 8) or b
            outputBuffer.putInt(argb)
        }
        
        outputBuffer.rewind()
    }

    fun doPredict(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, width: Int, height: Int) {
        try {
            // Create buffers for preprocessing
            val preprocessedBuffer = ByteBuffer.allocateDirect(512 * 512 * 3 * 4) // 3 channels, 4 bytes per float
            val floatBuffer = preprocessedBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            
            // Preprocess the input
            preprocess(inputBuffer, floatBuffer, width, height)

            // TODO: Run the model inference here
            // This will be implemented in the next step
            val shape = longArrayOf(1L, 3L, 512L, 512L)

            // We can't rewind enough
            floatBuffer.rewind()

            var tensorFromBuffer = OnnxTensor.createTensor(env, floatBuffer, shape)
            val result = session.run(Collections.singletonMap("input", tensorFromBuffer), setOf("output"))
            result.use {
                // Assuming outputArray is a 4D float array from ONNX model
                val outputArray = result.get(0).value as Array<Array<Array<FloatArray>>>

                // get the values of the 4D array
                val batchSize = outputArray.size
                val channels = outputArray[0].size
                val imageHeight = outputArray[0][0].size
                val imageWidth = outputArray[0][0][0].size

                // Calculate the total number of elements
                val totalElements = batchSize * channels * imageHeight * imageWidth

                // Create a FloatBuffer to hold the flattened data
                val outBuffer = FloatBuffer.allocate(totalElements)

                // Iterate through the 4D array and put each element into the FloatBuffer
                for (b in 0 until batchSize) {
                    for (c in 0 until channels) {
                        for (h in 0 until imageHeight) {
                            for (w in 0 until imageWidth) {
                                outBuffer.put(outputArray[b][c][h][w])
                            }
                        }
                    }
                }

                // Rewind the FloatBuffer to the beginning
                outBuffer.rewind()
                postprocess(outBuffer, outputBuffer)
            }


        }
        catch (e: OrtException) {
            Log.d("AnimeGan", "OrtException: " + e.message)
        }
    }
}