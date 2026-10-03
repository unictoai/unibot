package ai.unicto.unibot.connectors.qr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import ai.unicto.unibot.logging.AppLogger
import ai.unicto.unibot.util.EncryptedPrefsFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * QR tools — fully ON-DEVICE, no network at all.
 *
 * Generates QR codes with ZXing (already a dependency) and decodes QR/barcode
 * images from files. Nothing leaves the phone.
 */
object QrConnector {

    private const val TAG = "QrConnector"

    private const val PREFS_FILE = "qr_connector"
    private const val KEY_ENABLED = "enabled"

    sealed class ApiResult<out T> {
        data class Ok<T>(val value: T) : ApiResult<T>()
        data class Disabled(val hint: String = "QR tools are disabled. Ask the user to enable them in Settings → Connectors.") :
            ApiResult<Nothing>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, PREFS_FILE)
                .also { prefsRef = it }
        }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
    fun isConnected(context: Context): Boolean = isEnabled(context)

    /** Generate a QR code PNG, saved to the app cache. Returns the file path. */
    suspend fun generate(context: Context, text: String, sizePx: Int = 512): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            if (text.isBlank()) return@withContext ApiResult.Error("Give text to encode.")
            if (text.length > 2000) return@withContext ApiResult.Error("Text too long (max 2000 chars).")
            runCatching {
                val size = sizePx.coerceIn(128, 1024)
                val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
                val dir = File(context.cacheDir, "qr").apply { mkdirs() }
                val file = File(dir, "qr_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Keep the cache tidy.
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
                ApiResult.Ok("QR code saved: ${file.absolutePath}")
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[generate] ${e.message}")
                ApiResult.Error("QR generation failed: ${e.message}")
            }
        }

    /** Decode a QR/barcode from an image file. Returns the embedded text. */
    suspend fun decode(context: Context, imagePath: String): ApiResult<String> =
        withContext(Dispatchers.IO) {
            if (!isEnabled(context)) return@withContext ApiResult.Disabled()
            val file = File(imagePath)
            if (!file.exists()) return@withContext ApiResult.Error("Image not found: $imagePath")
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    ?: return@withContext ApiResult.Error("Could not read the image.")
                val w = bmp.width; val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val source = RGBLuminanceSource(w, h, pixels)
                val reader = MultiFormatReader()
                val result = try {
                    reader.decode(BinaryBitmap(HybridBinarizer(source)))
                } catch (_: Exception) {
                    reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
                }
                ApiResult.Ok("Decoded (${result.barcodeFormat}): ${result.text}")
            }.getOrElse { e ->
                AppLogger.warning(TAG, "[decode] ${e.message}")
                ApiResult.Error("Could not decode a code from that image.")
            }
        }
}
