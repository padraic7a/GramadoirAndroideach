package ie.cadhan.gramadoir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import ie.cadhan.gramadoir.databinding.ActivityMainBinding
import ie.cadhan.gramadoir.databinding.ItemErrorBinding
import ie.cadhan.gramadoir.databinding.ItemSpellingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
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
// Data model — represents a single GaelSpell spelling error
// GaelSpell returns a JSON array of two-element arrays:
// [ ["misspelled_word", ["suggestion1", "suggestion2", ...]], ... ]
// ---------------------------------------------------------------------------
data class SpellingError(
    val word: String,               // The misspelled word
    val suggestions: List<String>   // Ordered list of suggested corrections
)

// ---------------------------------------------------------------------------
// MainActivity
// ---------------------------------------------------------------------------
class MainActivity : AppCompatActivity() {

    // ViewBinding gives us type-safe access to every view in activity_main.xml
    private lateinit var binding: ActivityMainBinding
    private lateinit var historyManager: HistoryManager

    // OkHttp client for making HTTP requests; we reuse one instance for efficiency
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Gson for parsing the JSON responses from the APIs
    private val gson = Gson()

    // ---------------------------------------------------------------------------
    // GaelSpell state — tracks the current step-through spelling correction session
    // ---------------------------------------------------------------------------
    private var spellingErrors: List<SpellingError> = emptyList()  // All errors from last check
    private var currentSpellingIndex: Int = -1                      // Which error we're on (-1 = none)

    // Launcher for HistoryActivity — receives the selected text back when
    // the user taps "Use" on a history item
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedText = result.data?.getStringExtra(HistoryActivity.EXTRA_SELECTED_TEXT)
            if (!selectedText.isNullOrEmpty()) {
                // Load the selected text into Box 1
                binding.editTextIrish.setText(selectedText)
                // Clear any previous results
                clearAllResults()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflate the layout and set it as the content view
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        historyManager = HistoryManager(this)

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

        // --- Button: Clear everything ---
        binding.buttonClear.setOnClickListener {
            binding.editTextIrish.text?.clear()
            clearAllResults()
        }

        // --- Button: Translate (Irish → English via MyMemory) ---
        binding.buttonTranslate.setOnClickListener {
            val text = binding.editTextIrish.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_empty_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            translateText(text)
        }

        // --- Button: Spell check via GaelSpell ---
        binding.buttonSpell.setOnClickListener {
            val text = binding.editTextIrish.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_empty_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            hideKeyboard()
            checkSpelling(text)
        }

        // --- Button: Copy Irish text to clipboard and save to history ---
        binding.buttonCopyText.setOnClickListener {
            val text = binding.editTextIrish.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_empty_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard(text)
            // Save to history when the user copies their Irish text
            historyManager.saveItem(text)
            Toast.makeText(this, getString(R.string.msg_saved_to_history), Toast.LENGTH_SHORT).show()
        }

        // --- Button: Clear translation (Box 2) ---
        binding.buttonClearTranslation.setOnClickListener {
            binding.cardTranslation.visibility = View.GONE
            binding.textTranslation.text = ""
        }
    }

    // -----------------------------------------------------------------------
    // TOOLBAR: Inflate the menu into the toolbar
    // -----------------------------------------------------------------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    // -----------------------------------------------------------------------
    // TOOLBAR: Handle toolbar menu item clicks
    // -----------------------------------------------------------------------
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                historyLauncher.launch(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -----------------------------------------------------------------------
    // GAELSPELL: Check spelling via API
    // -----------------------------------------------------------------------
    private fun checkSpelling(text: String) {
        showSpellingLoading(true)
        binding.cardSpelling.visibility = View.GONE
        binding.cardError.visibility = View.GONE

        // Reset step-through state
        spellingErrors = emptyList()
        currentSpellingIndex = -1

        lifecycleScope.launch {
            try {
                val errors = withContext(Dispatchers.IO) { callGaelSpellApi(text) }
                showSpellingLoading(false)
                spellingErrors = errors
                if (errors.isEmpty()) {
                    // No spelling errors — show success message
                    displayNoSpellingErrors()
                } else {
                    // Start step-through from first error
                    currentSpellingIndex = 0
                    displayCurrentSpellingError()
                }
            } catch (e: IOException) {
                showSpellingLoading(false)
                showNetworkError(e.message ?: "Unknown error")
            }
        }
    }

    // -----------------------------------------------------------------------
    // GAELSPELL: Makes the actual HTTP POST to the GaelSpell API
    // Must be called from a background thread (via withContext(Dispatchers.IO))
    // GaelSpell returns a JSON array of two-element arrays:
    // [ ["misspelled_word", ["suggestion1", "suggestion2"]], ... ]
    // -----------------------------------------------------------------------
    @Throws(IOException::class)
    private fun callGaelSpellApi(text: String): List<SpellingError> {
        val requestBody = FormBody.Builder()
            .add("teacs", text)
            .build()

        val request = Request.Builder()
            .url("https://246874.xyz/api/gaelspell/1.0")
            .addHeader("X-Api-Key", BuildConfig.API_KEY)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response from server")

        // Parse the JSON array of [word, [suggestions]] pairs
        val rawList = gson.fromJson(responseBody, List::class.java)
        return rawList.map { item ->
            @Suppress("UNCHECKED_CAST")
            val pair = item as List<Any>
            val word = pair[0] as String
            val suggestions = (pair[1] as List<*>).map { it.toString() }
            SpellingError(word, suggestions)
        }
    }

    // -----------------------------------------------------------------------
    // GAELSPELL: Shows the "no errors" message
    // -----------------------------------------------------------------------
    private fun displayNoSpellingErrors() {
        binding.cardSpelling.visibility = View.VISIBLE
        binding.containerSpelling.removeAllViews()
        binding.textNoSpellingErrors.visibility = View.VISIBLE
    }

    // -----------------------------------------------------------------------
    // GAELSPELL: Displays the current spelling error in the step-through
    // Shows the misspelled word, suggestion chips, and a Keep button
    // -----------------------------------------------------------------------
    private fun displayCurrentSpellingError() {
        if (currentSpellingIndex < 0 || currentSpellingIndex >= spellingErrors.size) {
            // All errors stepped through — hide spelling card
            binding.cardSpelling.visibility = View.GONE
            Toast.makeText(
                this,
                getString(R.string.msg_spelling_complete),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val error = spellingErrors[currentSpellingIndex]

        binding.cardSpelling.visibility = View.VISIBLE
        binding.textNoSpellingErrors.visibility = View.GONE
        binding.containerSpelling.removeAllViews()

        // Inflate item_spelling.xml for this error
        val itemBinding = ItemSpellingBinding.inflate(
            layoutInflater, binding.containerSpelling, false
        )

        // Show the misspelled word
        itemBinding.textMisspelledWord.text =
            getString(R.string.spelling_error_label) + error.word

        // Add a chip for each suggestion
        error.suggestions.forEach { suggestion ->
            val chip = Chip(this)
            chip.text = suggestion
            chip.isClickable = true
            chip.isCheckable = false
            chip.setOnClickListener {
                // Replace the misspelled word in Box 1 with the chosen suggestion
                replaceWordInText(error.word, suggestion)
                // Move to the next error
                currentSpellingIndex++
                displayCurrentSpellingError()
            }
            itemBinding.chipGroupSuggestions.addView(chip)
        }

        // Add a "Keep" chip — leaves the word as-is and moves to next error
        val keepChip = Chip(this)
        keepChip.text = getString(R.string.button_keep)
        keepChip.isClickable = true
        keepChip.isCheckable = false
        keepChip.chipBackgroundColor =
            getColorStateList(android.R.color.darker_gray)
        keepChip.setOnClickListener {
            // Skip this error and move to the next one
            currentSpellingIndex++
            displayCurrentSpellingError()
        }
        itemBinding.chipGroupSuggestions.addView(keepChip)

        binding.containerSpelling.addView(itemBinding.root)
    }

    // -----------------------------------------------------------------------
    // GAELSPELL: Replaces the first occurrence of a misspelled word in Box 1
    // with the chosen suggestion, preserving the rest of the text
    // -----------------------------------------------------------------------
    private fun replaceWordInText(misspelled: String, replacement: String) {
        val currentText = binding.editTextIrish.text?.toString() ?: return
        // Replace the first occurrence of the misspelled word (whole word match)
        val updatedText = currentText.replaceFirst(
            Regex("\\b${Regex.escape(misspelled)}\\b"),
            replacement
        )
        binding.editTextIrish.setText(updatedText)
        // Move cursor to end
        binding.editTextIrish.setSelection(updatedText.length)
    }

    // -----------------------------------------------------------------------
    // GRAMADÓIR: API call — runs on a background thread, updates UI on main
    // -----------------------------------------------------------------------
    private fun checkGrammar(text: String) {
        showGrammarLoading(true)
        binding.cardResults.visibility = View.GONE
        binding.cardError.visibility = View.GONE

        // lifecycleScope.launch runs on the main thread but lets us call
        // withContext(Dispatchers.IO) to do network work off the main thread
        lifecycleScope.launch {
            try {
                val errors = withContext(Dispatchers.IO) { callGramadoirApi(text) }
                showGrammarLoading(false)
                displayGrammarResults(errors)
            } catch (e: IOException) {
                showGrammarLoading(false)
                showNetworkError(e.message ?: "Unknown error")
            }
        }
    }

    // -----------------------------------------------------------------------
    // GRAMADÓIR: Makes the actual HTTP POST to the Gramadóir API
    // Must be called from a background thread (via withContext(Dispatchers.IO))
    // -----------------------------------------------------------------------
    @Throws(IOException::class)
    private fun callGramadoirApi(text: String): List<GramadoirError> {
        // Build a form-encoded POST body:
        //   teacs  = the Irish text to check
        //   teanga = "en" means error messages returned in English
        val requestBody = FormBody.Builder()
            .add("teacs", text)
            .add("teanga", "en")
            .add("cliant", "gramadóir andróidigh")
            .build()

        val request = Request.Builder()
            .url("https://246874.xyz/api/gramadoir/1.0")
            .addHeader("X-Api-Key", BuildConfig.API_KEY)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response from server")

        // Parse the JSON array into a list of GramadoirError objects
        val listType = object : TypeToken<List<GramadoirError>>() {}.type
        return gson.fromJson(responseBody, listType) ?: emptyList()
    }

    // -----------------------------------------------------------------------
    // GRAMADÓIR: Displays the list of errors (or a "no errors" message)
    // -----------------------------------------------------------------------
    private fun displayGrammarResults(errors: List<GramadoirError>) {
        binding.cardResults.visibility = View.VISIBLE
        binding.containerErrors.removeAllViews()

        if (errors.isEmpty()) {
            binding.textNoErrors.visibility = View.VISIBLE
        } else {
            binding.textNoErrors.visibility = View.GONE
            for (error in errors) addErrorCard(error)
        }
    }

    // -----------------------------------------------------------------------
    // GRAMADÓIR: Inflates item_error.xml and populates it with one error
    // -----------------------------------------------------------------------
    private fun addErrorCard(error: GramadoirError) {
        val itemBinding = ItemErrorBinding.inflate(layoutInflater, binding.containerErrors, false)
        itemBinding.textErrorMessage.text = error.msg

        // Gramadóir HTML-encodes some characters — decode the common ones
        val decodedContext = error.context
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        itemBinding.textContext.text = getString(R.string.error_context_label) + decodedContext
        itemBinding.textErrorWord.text = error.errortext

        binding.containerErrors.addView(itemBinding.root)
    }

    // -----------------------------------------------------------------------
    // TRANSLATION: Calls MyMemory API (Irish → English)
    // -----------------------------------------------------------------------
    private fun translateText(text: String) {
        showTranslationLoading(true)
        // Hide the previous translation while we fetch a new one
        binding.cardTranslation.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val translation = withContext(Dispatchers.IO) { callMyMemoryApi(text) }
                showTranslationLoading(false)
                displayTranslation(translation)
            } catch (e: IOException) {
                showTranslationLoading(false)
                showTranslationError(e.message ?: "Unknown error")
            }
        }
    }

    // -----------------------------------------------------------------------
    // TRANSLATION: Makes the GET request to the MyMemory API
    // MyMemory uses a simple GET with query parameters — no API key needed
    // Language pair "ga|en" means Irish → English
    // Must be called from a background thread (via withContext(Dispatchers.IO))
    // -----------------------------------------------------------------------
    @Throws(IOException::class)
    private fun callMyMemoryApi(text: String): String {
        // MyMemory API: GET https://api.mymemory.translated.net/get?q=<text>&langpair=ga|en
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=ga|en"

        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")

        val responseBody = response.body?.string()
            ?: throw IOException("Empty response from server")

        // MyMemory returns JSON like:
        // { "responseData": { "translatedText": "..." }, "responseStatus": 200, ... }
        val jsonObject = JsonParser.parseString(responseBody).asJsonObject
        val status = jsonObject.get("responseStatus")?.asInt
            ?: throw IOException("No status in response")

        if (status != 200) throw IOException("Translation API returned status $status")

        return jsonObject
            .getAsJsonObject("responseData")
            ?.get("translatedText")
            ?.asString
            ?: throw IOException("No translation in response")
    }

    // -----------------------------------------------------------------------
    // TRANSLATION: Shows the translated text in Box 2
    // -----------------------------------------------------------------------
    private fun displayTranslation(translation: String) {
        binding.textTranslation.text = translation
        binding.cardTranslation.visibility = View.VISIBLE
    }

    // -----------------------------------------------------------------------
    // TRANSLATION: Shows a translation-specific error message
    // -----------------------------------------------------------------------
    private fun showTranslationError(message: String) {
        binding.cardError.visibility = View.VISIBLE
        binding.textError.text = getString(R.string.error_translation, message)
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
    // Clears all results — spelling, grammar, translation and error cards
    // Called by the Clear button and when a history item is loaded
    // -----------------------------------------------------------------------
    private fun clearAllResults() {
        binding.cardResults.visibility = View.GONE
        binding.cardError.visibility = View.GONE
        binding.cardTranslation.visibility = View.GONE
        binding.cardSpelling.visibility = View.GONE
        binding.containerErrors.removeAllViews()
        binding.containerSpelling.removeAllViews()
        spellingErrors = emptyList()
        currentSpellingIndex = -1
    }

    // -----------------------------------------------------------------------
    // Shows/hides the spelling loading bar and disables the Spell button
    // -----------------------------------------------------------------------
    private fun showSpellingLoading(loading: Boolean) {
        binding.progressBarSpelling.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonSpell.isEnabled = !loading
    }

    // -----------------------------------------------------------------------
    // Shows/hides the Gramadóir loading bar and disables the Check button
    // -----------------------------------------------------------------------
    private fun showGrammarLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonCheck.isEnabled = !loading
    }

    // -----------------------------------------------------------------------
    // Shows/hides the translation loading bar and disables Translate button
    // -----------------------------------------------------------------------
    private fun showTranslationLoading(loading: Boolean) {
        binding.progressBarTranslation.visibility = if (loading) View.VISIBLE else View.GONE
        binding.buttonTranslate.isEnabled = !loading
    }

    // -----------------------------------------------------------------------
    // Shows a network/server error message in the error card
    // -----------------------------------------------------------------------
    private fun showNetworkError(message: String) {
        binding.cardError.visibility = View.VISIBLE
        binding.textError.text = getString(R.string.error_network, message)
    }

    // -----------------------------------------------------------------------
    // Hides the soft keyboard (called before making any API request)
    // -----------------------------------------------------------------------
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}