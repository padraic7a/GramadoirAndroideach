package ie.cadhan.gramadoir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textview.MaterialTextView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ie.cadhan.gramadoir.databinding.ActivityMainBinding
import ie.cadhan.gramadoir.databinding.ItemErrorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// Data model — matches the JSON the Gramadóir API returns for each error
// ---------------------------------------------------------------------------
data class GramadoirError(
    val msg: String,          // Human-readable error message, e.g. "Lenition missing"
    val errortext: String,    // The specific word/phrase that is wrong
    val context: String,      // The surrounding sentence for context
    val ruleId: String        // Internal rule ID, e.g. "Lingua::GA::Gramadoir/SEIMHIU"
)

// ---------------------------------------------------------------------------
// MainActivity
// ---------------------------------------------------------------------------
class MainActivity : AppCompatActivity() {

    // ViewBinding gives us type-safe access to every view in activity_main.xml
    private lateinit var binding: ActivityMainBinding

    // OkHttp client for making HTTP requests; we reuse one instance for efficiency
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Gson for parsing the JSON response from the API
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout and set it as the content view
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set the toolbar as our action bar
        setSupportActionBar(binding.toolbar)

        // --- Button: Check grammar ---
        binding.buttonCheck.setOnClickListener {
            val text = binding.editTextIrish.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_empty_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            checkGrammar(text)
        }

        // --- Button: Clear all ---
        binding.buttonClear.setOnClickListener {
            binding.editTextIrish.text?.clear()
            binding.cardResults.visibility = View.GONE
            binding.cardError.visibility = View.GONE
            binding.containerErrors.removeAllViews()
        }

        // --- Button: Copy original text to clipboard ---
        binding.buttonCopyText.setOnClickListener {
            val text = binding.editTextIrish.text?.toString() ?: ""
            copyToClipboard(text)
        }
    }

    // -----------------------------------------------------------------------
    // API call — runs on a background thread, updates UI on the main thread
    // -----------------------------------------------------------------------
    private fun checkGrammar(text: String) {
        // Show loading spinner, hide previous results
        showLoading(true)
        binding.cardResults.visibility = View.GONE
        binding.cardError.visibility = View.GONE

        // lifecycleScope.launch runs on the main thread but lets us call
        // 'withContext(Dispatchers.IO)' to do network work off the main thread
        lifecycleScope.launch {
            try {
                val errors = withContext(Dispatchers.IO) {
                    callGramadoirApi(text)
                }
                // Back on the main thread — update the UI
                showLoading(false)
                displayResults(errors, text)
            } catch (e: IOException) {
                showLoading(false)
                showNetworkError(e.message ?: "Unknown error")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Makes the actual HTTP POST to the Gramadóir API
    // Must be called from a background thread (via withContext(Dispatchers.IO))
    // -----------------------------------------------------------------------
    @Throws(IOException::class)
    private fun callGramadoirApi(text: String): List<GramadoirError> {
        // Build a form-encoded POST body with the two required parameters:
        //   teacs  = the Irish text to check (URL-encoded automatically by OkHttp)
        //   teanga = "en" means error messages will be returned in English
        val requestBody = FormBody.Builder()
            .add("teacs", text)
            .add("teanga", "en")
            .build()

        val request = Request.Builder()
            .url("https://cadhan.com/api/gramadoir/1.0")
            .post(requestBody)
            .build()

        // Execute the call (blocking — fine because we're on a background thread)
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("Server returned HTTP ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response from server")

        // Parse the JSON array into a list of GramadoirError objects
        val listType = object : TypeToken<List<GramadoirError>>() {}.type
        return gson.fromJson(responseBody, listType) ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // Displays the list of errors (or a "no errors" message) in the UI
    // -----------------------------------------------------------------------
    private fun displayResults(errors: List<GramadoirError>, originalText: String) {
        binding.cardResults.visibility = View.VISIBLE
        binding.containerErrors.removeAllViews()

        if (errors.isEmpty()) {
            // Great news — no errors!
            binding.textNoErrors.visibility = View.VISIBLE
        } else {
            binding.textNoErrors.visibility = View.GONE

            // Add one card per error
            for (error in errors) {
                addErrorCard(error)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Inflates item_error.xml and populates it with one error's details
    // -----------------------------------------------------------------------
    private fun addErrorCard(error: GramadoirError) {
        // ItemErrorBinding is generated from res/layout/item_error.xml
        val itemBinding = ItemErrorBinding.inflate(layoutInflater, binding.containerErrors, false)

        // The grammar rule message, e.g. "Lenition missing"
        itemBinding.textErrorMessage.text = error.msg

        // The surrounding sentence so the user knows where the error is
        // Gramadóir HTML-encodes some characters — decode the common ones
        val decodedContext = error.context
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        itemBinding.textContext.text = getString(R.string.error_context_label) + decodedContext

        // The specific offending word/phrase
        itemBinding.textErrorWord.text = error.errortext

        binding.containerErrors.addView(itemBinding.root)
    }

    // -----------------------------------------------------------------------
    // Copies the given text to the system clipboard and shows a toast
    // -----------------------------------------------------------------------
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Irish text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // Shows or hides the loading progress bar and disables the Check button
    // -----------------------------------------------------------------------
    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonCheck.isEnabled = !loading
    }

    // -----------------------------------------------------------------------
    // Shows a network/server error message in the error card
    // -----------------------------------------------------------------------
    private fun showNetworkError(message: String) {
        binding.cardError.visibility = View.VISIBLE
        binding.textError.text = getString(R.string.error_network, message)
    }

    // -----------------------------------------------------------------------
    // Hides the soft keyboard (called before making the API request)
    // -----------------------------------------------------------------------
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }
}
