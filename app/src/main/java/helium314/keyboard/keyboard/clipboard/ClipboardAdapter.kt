// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

class ClipboardAdapter(
    val clipboardLayoutParams: ClipboardLayoutParams,
    val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    /** Invoked when the user taps "Edit clip" from the long-press menu. */
    var onEditRequested: ((id: Long, currentText: String, currentTrigger: String) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(getItem(position))
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount() = clipboardHistoryManager?.getHistorySize() ?: 0

    inner class ViewHolder(view: View) :
        RecyclerView.ViewHolder(view),
        View.OnClickListener,
        View.OnTouchListener,
        View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentView: TextView
        private val triggerLabelView: TextView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentView = view.findViewById<TextView>(R.id.clipboard_entry_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            clipboardLayoutParams.setItemProperties(view)
            Settings.getValues().mColors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)

            triggerLabelView = view.findViewById(R.id.clipboard_entry_trigger_label)
            triggerLabelView.setTextColor(itemTextColor)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            itemView.tag = historyEntry?.id ?: -1L
            contentView.text = historyEntry?.text?.take(1000)
            pinnedIconView.visibility = if (historyEntry?.isPinned == true) View.VISIBLE else View.GONE
            val trigger = historyEntry?.triggerKey?.takeIf { it.isNotBlank() }
            if (trigger != null) {
                triggerLabelView.text = trigger
                triggerLabelView.visibility = View.VISIBLE
            } else {
                triggerLabelView.visibility = View.GONE
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val id = view.tag as? Long ?: return false
                keyEventListener.onKeyDown(id)
            }
            return false
        }

        override fun onClick(view: View) {
            val id = view.tag as? Long ?: return
            keyEventListener.onKeyUp(id)
        }

        override fun onLongClick(view: View): Boolean {
            val id = view.tag as? Long ?: return false
            showContextMenu(view, id)
            return true
        }

        // ── Context menu ──────────────────────────────────────────────────────

        private fun showContextMenu(anchor: View, id: Long) {
            val ctx = anchor.context
            val manager = clipboardHistoryManager ?: return
            val entry = runCatching { manager.getHistoryEntryContent(id) }.getOrNull() ?: return

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4.dp(ctx), 4.dp(ctx), 4.dp(ctx), 4.dp(ctx))
            }

            val popup = PopupWindow(ctx).apply {
                contentView = layout
                isFocusable = true
                isOutsideTouchable = true
                elevation = 8f
                // IME windows require TYPE_APPLICATION_ATTACHED_DIALOG so the popup
                // can attach to the keyboard window; without this, showAsDropDown
                // silently no-ops on most Android versions inside an InputMethodService.
                windowLayoutType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                height = LinearLayout.LayoutParams.WRAP_CONTENT
            }

            fun item(label: String, action: () -> Unit): TextView = TextView(ctx).apply {
                text = label
                textSize = 14f
                setPadding(16.dp(ctx), 12.dp(ctx), 16.dp(ctx), 12.dp(ctx))
                setTextColor(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { popup.dismiss(); action() }
            }

            fun divider(): View = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    80.dp(ctx), 1 // Use a fixed-ish width or just let it be small
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                setBackgroundColor(0x33888888)
            }

            // Pin / Unpin
            val pinLabel = if (entry.isPinned)
                ctx.getString(R.string.clipboard_menu_unpin)
            else
                ctx.getString(R.string.clipboard_menu_pin)
            layout.addView(item(pinLabel) {
                manager.toggleClipPinned(id)
            })

            layout.addView(divider())

            // Edit
            layout.addView(item(ctx.getString(R.string.clipboard_menu_edit)) {
                onEditRequested?.invoke(id, entry.text, entry.triggerKey)
            })

            layout.addView(divider())

            // Delete
            layout.addView(item(ctx.getString(R.string.clipboard_menu_delete)) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    manager.removeEntry(pos)
                    notifyItemRemoved(pos)
                }
            })

            // Theme the popup background
            Settings.getValues().mColors.setBackground(layout, ColorType.STRIP_BACKGROUND)

            anchor.post {
                val layoutLocation = IntArray(2)
                anchor.getLocationInWindow(layoutLocation)
                
                var p = anchor.parent
                while (p != null && p !is ClipboardHistoryView) p = p.parent
                val clipboardView = p as? View
                
                val clipboardLocation = IntArray(2)
                clipboardView?.getLocationInWindow(clipboardLocation)
                
                val relativeY = layoutLocation[1] - clipboardLocation[1]
                val limit = clipboardView?.height ?: anchor.rootView.height
                
                layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val popupHeight = layout.measuredHeight
                val popupWidth = layout.measuredWidth
                val anchorHeight = anchor.height
                
                popup.width = popupWidth
                popup.height = popupHeight

                if (relativeY + anchorHeight + popupHeight > limit && relativeY > popupHeight) {
                    popup.showAsDropDown(anchor, 0, -anchorHeight - popupHeight, Gravity.START)
                } else {
                    popup.showAsDropDown(anchor, 0, 0, Gravity.START)
                }
            }
        }

        private fun Int.dp(ctx: Context): Int =
            (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }
}
