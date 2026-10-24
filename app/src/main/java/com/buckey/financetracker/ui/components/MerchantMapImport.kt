package com.buckey.financetracker.ui.components

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.importer.MerchantMapImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Some document providers report .json as octet-stream or plain text; filtering
// on application/json alone can make the file unselectable.
private val MERCHANT_MAP_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream"
)

private const val TAG = "MerchantMapImport"

/**
 * Returns a function that lets the user pick `merchant_map.json` and imports it
 * (Stage 7). The Storage Access Framework hands over just that file, so no
 * storage permission is needed (rule 11). [onResult] gets "Importing…", then
 * the import summary or an "Import failed: …" message.
 *
 * Shared by Settings → Import Merchant Map and the home screen's first-run
 * "Import statements".
 */
@Composable
fun rememberMerchantMapImport(database: AppDatabase, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onResult("Importing…")
        scope.launch {
            val message = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                } ?: throw IllegalStateException("Could not open the selected file.")

                MerchantMapImporter.import(database, json).summary()
            } catch (e: MerchantMapImporter.InvalidMapException) {
                "Import failed: ${e.message}"
            } catch (e: Exception) {
                Log.e(TAG, "Merchant map import failed", e)
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            onResult(message)
        }
    }
    return { launcher.launch(MERCHANT_MAP_MIME_TYPES) }
}
