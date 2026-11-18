package com.pickupwatch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pickupwatch.data.PickupCode
import com.pickupwatch.databinding.ItemPickupCodeBinding
import java.text.SimpleDateFormat
import java.util.*

class PickupAdapter(
    private val onPicked: (PickupCode) -> Unit
) : ListAdapter<PickupCode, PickupAdapter.ViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPickupCodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPickupCodeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(code: PickupCode) {
            val label = buildString {
                if (!code.courier.isNullOrEmpty()) {
                    append(code.courier)
                    if (!code.station.isNullOrEmpty()) append("·")
                }
                append(code.station ?: "未知网点")
            }
            binding.tvStation.text = label
            binding.tvCode.text = code.code
            binding.tvTime.text = dateFormat.format(Date(code.eventTime))

            binding.btnPicked.setOnClickListener {
                onPicked(code)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<PickupCode>() {
        override fun areItemsTheSame(oldItem: PickupCode, newItem: PickupCode): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PickupCode, newItem: PickupCode): Boolean =
            oldItem == newItem
    }
}