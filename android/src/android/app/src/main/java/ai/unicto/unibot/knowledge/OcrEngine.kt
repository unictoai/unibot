package ai.unicto.unibot.knowledge

/**
 * Result of one OCR pass.
 */
data class OcrResult(
    /** Recognized text, "" when nothing was found or recognition failed. */
    val text: String,
    val confidence: Float? = null,
)

/**
 * Whether on-device text recognition can run here.
 */
sealed interface OcrAvailability {
    data object Available : OcrAvailability
    data class Unavailable(val reason: String) : OcrAvailability
}

/**
 * On-device OCR (item 50). Implementations take raw image bytes and return
 * recognized text. The contract is honest about availability: when the engine
 * can't run, [availability] says why instead of failing silently.
 */
interface OcrEngine {
    val availability: OcrAvailability
    suspend fun recognize(imageBytes: ByteArray): OcrResult
}

/**
 * Fallback engine used when no OCR backend is present. Never throws — reports
 * unavailability so the UI can offer the graceful path (keep the photo as an
 * attachment instead).
 */
object NoopOcrEngine : OcrEngine {
    override val availability: OcrAvailability = OcrAvailability.Unavailable(
        "On-device text recognition needs Google Play services, which isn't " +
            "available on this device.",
    )

    override suspend fun recognize(imageBytes: ByteArray): OcrResult = OcrResult("")
}

/**
 * Picks the best OCR engine for this device: Play Services ML Kit text
 * recognition when present, [NoopOcrEngine] otherwise.
 */
object OcrEngines {
    fun bestAvailable(context: android.content.Context): OcrEngine {
        return try {
            val engine = PlayServicesOcrEngine(context)
            if (engine.availability is OcrAvailability.Available) engine else NoopOcrEngine
        } catch (_: Throwable) {
            NoopOcrEngine
        }
    }
}
