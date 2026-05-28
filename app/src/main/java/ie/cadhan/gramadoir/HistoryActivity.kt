package ie.cadhan.gramadoir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ie.cadhan.gramadoir.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyManager: HistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarHistory)

        // Back arrow navigates back to MainActivity
        binding.toolbarHistory.setNavigationOnClickListener {
            finish()
        }

        // Handle the "Clear All" menu item
        binding.toolbarHistory.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_all) {
                confirmClearAll()
                true
            } else false
        }

        historyManager = HistoryManager(this)
        setupRecyclerView()
        updateEmptyState()
    }

    // -----------------------------------------------------------------------
    // Sets up the RecyclerView with the history adapter
    // -----------------------------------------------------------------------
    private fun setupRecyclerView() {
        val history = historyManager.getHistory()

        adapter = HistoryAdapter(
            items = history,
            onUse = { text -> useItem(text) },
            onCopy = { text -> copyItem(text) },
            onDelete = { index -> confirmDeleteItem(index) }
        )

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
    }

    // -----------------------------------------------------------------------
    // "Use" — sends the text back to MainActivity and finishes this screen
    // -----------------------------------------------------------------------
    private fun useItem(text: String) {
        val intent = Intent()
        intent.putExtra(EXTRA_SELECTED_TEXT, text)
        setResult(RESULT_OK, intent)
        finish()
    }

    // -----------------------------------------------------------------------
    // "Copy" — copies to clipboard and shows a toast
    // -----------------------------------------------------------------------
    private fun copyItem(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Irish text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // "Delete" — asks for confirmation then removes the item
    // -----------------------------------------------------------------------
    private fun confirmDeleteItem(index: Int) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.msg_confirm_delete))
            .setPositiveButton(getString(R.string.button_confirm)) { _, _ ->
                historyManager.deleteItem(index)
                adapter.removeItem(index)
                updateEmptyState()
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    // -----------------------------------------------------------------------
    // "Clear All" — asks for confirmation then clears all history
    // -----------------------------------------------------------------------
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.msg_confirm_clear_all))
            .setPositiveButton(getString(R.string.button_confirm)) { _, _ ->
                historyManager.clearAll()
                adapter.clearAll()
                updateEmptyState()
            }
            .setNegativeButton(getString(R.string.button_cancel), null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Shows the empty state message when there are no history items
    // -----------------------------------------------------------------------
    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            binding.textEmpty.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
        } else {
            binding.textEmpty.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
        }
    }

    companion object {
        const val EXTRA_SELECTED_TEXT = "selected_text"
    }
}