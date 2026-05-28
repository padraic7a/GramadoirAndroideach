package ie.cadhan.gramadoir

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * HistoryManager handles saving, loading, and deleting history items
 * using SharedPreferences. Each history item is an Irish text string.
 * Maximum 25 items are stored — oldest drops off when the limit is reached.
 */
class HistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // -----------------------------------------------------------------------
    // Load the full history list from SharedPreferences
    // Returns a list with the most recent item first
    // -----------------------------------------------------------------------
    fun getHistory(): MutableList<String> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<String>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    // -----------------------------------------------------------------------
    // Save a new item to the top of the history list
    // If the text already exists in history, move it to the top instead
    // If we're at the limit of 25, drop the oldest item
    // -----------------------------------------------------------------------
    fun saveItem(text: String) {
        val history = getHistory()

        // Remove duplicate if it already exists
        history.remove(text)

        // Add to the top of the list
        history.add(0, text)

        // Trim to maximum 25 items
        while (history.size > MAX_ITEMS) {
            history.removeAt(history.size - 1)
        }

        persist(history)
    }

    // -----------------------------------------------------------------------
    // Delete a single item by its position in the list
    // -----------------------------------------------------------------------
    fun deleteItem(index: Int) {
        val history = getHistory()
        if (index in history.indices) {
            history.removeAt(index)
            persist(history)
        }
    }

    // -----------------------------------------------------------------------
    // Clear the entire history
    // -----------------------------------------------------------------------
    fun clearAll() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // -----------------------------------------------------------------------
    // Serialise the list back to JSON and store it
    // -----------------------------------------------------------------------
    private fun persist(history: List<String>) {
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history)).apply()
    }

    companion object {
        private const val PREFS_NAME = "gramadoir_prefs"
        private const val KEY_HISTORY = "history"
        private const val MAX_ITEMS = 25
    }
}