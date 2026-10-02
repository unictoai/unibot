package ai.unicto.unibot.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.app.assist.AssistStructure
import android.widget.RemoteViews
import ai.unicto.unibot.R
import ai.unicto.unibot.logging.AppLogger

/**
 * unibot's autofill service — strictly opt-in, on-device only.
 *
 * Two gates, both required:
 *  1. [AutofillProfile.enabled] — the in-app toggle (default OFF).
 *  2. The user picks unibot in Android's system autofill settings
 *     (Settings → System → Autofill service) — Android enforces this itself;
 *     the OS will not route fill requests here otherwise.
 *
 * Fills name / email / phone from the local profile into fields the OS
 * identifies by autofill hint or input type. Nothing is read from anywhere
 * else, nothing is saved from forms ([onSaveRequest] cancels), nothing is
 * networked. The profile values never leave the phone.
 */
class UnibotAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val profile = AutofillProfileStore.get(this).profile.value
        if (!profile.enabled) {
            callback.onSuccess(null)
            return
        }
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val fields = findFillableFields(structure)
        if (fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val response = FillResponse.Builder()
        var added = 0
        for ((fieldId, kind) in fields) {
            val value = when (kind) {
                FieldKind.NAME -> profile.name
                FieldKind.EMAIL -> profile.email
                FieldKind.PHONE -> profile.phone
            }
            if (value.isBlank()) continue
            val presentation = RemoteViews(packageName, R.layout.autofill_dataset).apply {
                setTextViewText(R.id.autofill_dataset_text, value)
            }
            response.addDataset(
                Dataset.Builder(presentation)
                    .setValue(fieldId, AutofillValue.forText(value))
                    .build(),
            )
            added++
        }
        if (added == 0) {
            callback.onSuccess(null)
        } else {
            runCatching { callback.onSuccess(response.build()) }
                .onFailure {
                    AppLogger.warning(TAG, "fill response failed: ${it.message}")
                    callback.onSuccess(null)
                }
        }
    }

    /** Never learn from forms: unibot does not save credentials or form data. */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    private enum class FieldKind { NAME, EMAIL, PHONE }

    private fun findFillableFields(structure: AssistStructure): List<Pair<AutofillId, FieldKind>> {
        val out = mutableListOf<Pair<AutofillId, FieldKind>>()
        val count = structure.windowNodeCount
        for (i in 0 until count) {
            val node = structure.getWindowNodeAt(i).rootViewNode ?: continue
            traverse(node, out)
        }
        return out
    }

    private fun traverse(node: AssistStructure.ViewNode, out: MutableList<Pair<AutofillId, FieldKind>>) {
        val id = node.autofillId
        if (id != null) {
            classify(node)?.let { kind -> out += id to kind }
        }
        val children = node.childCount
        for (i in 0 until children) {
            traverse(node.getChildAt(i), out)
        }
    }

    private fun classify(node: AssistStructure.ViewNode): FieldKind? {
        val hints = node.autofillHints?.map { it.lowercase() } ?: emptyList()
        for (h in hints) {
            when {
                h.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS) -> return FieldKind.EMAIL
                h.contains("email") -> return FieldKind.EMAIL
                h.contains(View.AUTOFILL_HINT_PHONE) -> return FieldKind.PHONE
                h.contains("phone") || h.contains("tel") -> return FieldKind.PHONE
                h.contains(View.AUTOFILL_HINT_NAME) -> return FieldKind.NAME
                h.contains("name") && !h.contains("username") -> return FieldKind.NAME
            }
        }
        // Fallback: input type + view id entry naming.
        val inputType = node.inputType
        val emailVariation = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        if (inputType and emailVariation == emailVariation) return FieldKind.EMAIL
        val idEntry = node.idEntry?.lowercase().orEmpty()
        return when {
            "email" in idEntry -> FieldKind.EMAIL
            "phone" in idEntry || "tel" in idEntry || "mobile" in idEntry -> FieldKind.PHONE
            "name" in idEntry && "username" !in idEntry -> FieldKind.NAME
            else -> null
        }
    }

    companion object {
        private const val TAG = "Autofill"
    }
}
