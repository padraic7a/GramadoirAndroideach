package ie.cadhan.gramadoir

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ie.cadhan.gramadoir.databinding.ItemHistoryBinding

/**
 * RecyclerView adapter that displays the list of saved history items.
 * Each item has three buttons: Delete, Copy, and Use.
 */
class HistoryAdapter(
    private val items: MutableList<String>,
    private val onUse: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    inner class HistoryViewHolder(val binding: ItemHistoryBinding)
        : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val text = items[position]
        holder.binding.textHistoryItem.text = text

        holder.binding.buttonUseItem.setOnClickListener {
            onUse(text)
        }

        holder.binding.buttonCopyItem.setOnClickListener {
            onCopy(text)
        }

        holder.binding.buttonDeleteItem.setOnClickListener {
            // Use adapterPosition rather than position as position can be stale
            // after notifyItemRemoved calls
            onDelete(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size

    // -----------------------------------------------------------------------
    // Remove an item from the displayed list and animate it out
    // -----------------------------------------------------------------------
    fun removeItem(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    // -----------------------------------------------------------------------
    // Replace all items (used when clearing all history)
    // -----------------------------------------------------------------------
    fun clearAll() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }
}