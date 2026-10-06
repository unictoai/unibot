package ai.unicto.unibot.knowledge

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR via the Play Services ML Kit text recognizer (item 50).
 *
 * The model downloads on demand through Play services — nothing ships in the
 * APK and no image bytes leave the device. Construction probes availability;
 * [OcrEngines.bestAvailable] falls back to [NoopOcrEngine] when Play services
 * is missing (e.g. de-Googled ROMs), so callers never crash on the class.
 */
class PlayServicesOcrEngine(context: Context) : OcrEngine {

    override val availability: OcrAvailability = try {
        // Probes that the Play Services text-recognition module resolves.
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrAvailability.Available
    } catch (t: Throwable) {
        OcrAvailability.Unavailable(
            "Text recognition isn't available: ${t.message ?: t.javaClass.simpleName}",
        )
    }

    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(imageBytes: ByteArray): OcrResult {
        if (availability is OcrAvailability.Unavailable) return OcrResult("")
        return suspendCancellableCoroutine { cont ->
            try {
                // Downscale huge camera photos: OCR accuracy holds at ~2 MP
                // and a 12 MP bitmap is 48 MB the phone doesn't need to hold.
                val bitmap = decodeBounded(imageBytes, maxDimension = 2048)
                    ?: run { cont.resume(OcrResult("")); return@suspendCancellableCoroutine }
                val image = InputImage.fromBitmap(bitmap, 0)
                client.process(image)
                    .addOnSuccessListener { visionText ->
                        cont.resume(OcrResult(visionText.text.trim()))
                    }
                    .addOnFailureListener {
                        cont.resume(OcrResult(""))
                    }
            } catch (_: Throwable) {
                cont.resume(OcrResult(""))
            }
        }
    }

    private fun decodeBounded(bytes: ByteArray, maxDimension: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxDimension || h / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
