package dev.upihub

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.upihub.databinding.ActivityManageAppsBinding
import dev.upihub.databinding.ItemManageAppBinding

/**
 * Lets the user choose which installed UPI apps appear in the payment list
 * and in what order. Choices are saved as they are made.
 */
class ManageAppsActivity : AppCompatActivity() {
    private lateinit var b: ActivityManageAppsBinding
    private lateinit var prefs: Prefs
    private val apps = mutableListOf<UpiApp>()
    private val adapter = Adapter()
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityManageAppsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.appList.updatePadding(bottom = bars.bottom + dp(16))
            insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sortAz -> {
                    apps.sortBy { it.label.lowercase() }
                    saveOrder()
                    adapter.notifyItemRangeChanged(0, apps.size)
                    true
                }
                R.id.resetList -> {
                    prefs.resetAppList()
                    reload()
                    true
                }
                else -> false
            }
        }

        b.appList.layoutManager = LinearLayoutManager(this)
        b.appList.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                saveOrder()
            }
        })
        touchHelper.attachToRecyclerView(b.appList)
        reload()
    }

    /** Moves one app; the order is saved when the drag ends. */
    internal fun move(from: Int, to: Int) {
        if (from == to || from !in apps.indices || to !in apps.indices) return
        apps.add(to, apps.removeAt(from))
        adapter.notifyItemMoved(from, to)
    }

    internal fun saveOrder() {
        prefs.appOrder = apps.map { it.packageName }
    }

    private fun reload() {
        val fresh = UpiApps.discover(this, prefs)
        val oldSize = apps.size
        apps.clear()
        adapter.notifyItemRangeRemoved(0, oldSize)
        apps.addAll(fresh)
        adapter.notifyItemRangeInserted(0, apps.size)
        b.emptyText.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val b: ItemManageAppBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemManageAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = apps.size

        @SuppressLint("ClickableViewAccessibility") // Long-press anywhere on the row also drags.
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = apps[position]
            val isDefault = app.packageName == prefs.defaultPackage
            holder.b.label.text = app.label
            if (app.icon != null) holder.b.icon.setImageDrawable(app.icon)
            else holder.b.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.b.dragHandle.contentDescription = getString(R.string.reorder, app.label)
            holder.b.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                false
            }

            holder.b.visibleSwitch.setOnCheckedChangeListener(null)
            holder.b.visibleSwitch.isChecked = app.visible
            // The default app is always offered; change the default first to hide it.
            holder.b.visibleSwitch.visibility = if (isDefault) View.INVISIBLE else View.VISIBLE
            holder.b.visibleSwitch.contentDescription = app.label
            holder.b.status.setText(
                when {
                    isDefault -> R.string.default_always_shown
                    app.visible -> R.string.app_shown
                    else -> R.string.app_hidden
                },
            )
            holder.b.icon.alpha = if (app.visible) 1f else 0.4f
            holder.b.label.alpha = if (app.visible) 1f else 0.6f
            holder.b.visibleSwitch.setOnCheckedChangeListener { _, checked ->
                val index = holder.bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                prefs.setVisible(apps[index].packageName, checked)
                apps[index] = apps[index].copy(visible = checked)
                notifyItemChanged(index)
            }
        }
    }
}
