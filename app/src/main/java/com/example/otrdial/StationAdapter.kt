package com.example.otrdial

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.otrdial.databinding.StationItemBinding

class StationAdapter(
    private val onPlay: (Station) -> Unit,
    private val isFavourite: (Station) -> Boolean,
    private val onFavourite: (Station) -> Unit
) : RecyclerView.Adapter<StationAdapter.Holder>() {

    private var items: List<Station> = emptyList()

    fun submit(newItems: List<Station>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class Holder(val binding: StationItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = StationItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val station = items[position]
        holder.binding.stationName.text = station.name
        holder.binding.stationDetails.text = "${station.network}  •  ${station.genre}"
        holder.binding.stationInitials.text = initials(station.name)
        holder.binding.favMark.text = if (isFavourite(station)) "♥" else "♡"
        holder.binding.root.setOnClickListener { onPlay(station) }
        holder.binding.favMark.setOnClickListener {
            onFavourite(station)
        }
    }

    private fun initials(name: String): String = name
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}
