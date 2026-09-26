package com.example.otrdial

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import com.example.otrdial.databinding.StationItemBinding

class StationAdapter(
    private val onPlay: (Station) -> Unit,
    private val isFavourite: (Station) -> Boolean,
    private val onFavourite: (Station) -> Unit,
    private val onInfo: (Station) -> Unit
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
        holder.binding.stationInitials.setImageDrawable(StationArt.drawable(holder.itemView.context, station))
        holder.binding.favMark.text = if (isFavourite(station)) "♥" else "♡"
        holder.binding.favMark.contentDescription = if (isFavourite(station)) "Remove ${station.name} from favourites" else "Favourite ${station.name}"
        holder.binding.root.setOnClickListener { onPlay(station) }
        holder.binding.favMark.setOnClickListener {
            onFavourite(station)
        }
        holder.binding.infoMark.setOnClickListener { onInfo(station) }
    }

    private fun initials(name: String): String = name
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }

    private fun genreColour(genre: String): String = when {
        genre.contains("Comedy", true) -> "#C88719"
        genre.contains("Mystery", true) || genre.contains("Suspense", true) -> "#665191"
        genre.contains("Crime", true) || genre.contains("Detective", true) -> "#9B2C3B"
        genre.contains("Western", true) -> "#A65E28"
        genre.contains("Sci-Fi", true) || genre.contains("Horror", true) -> "#315A78"
        genre.contains("Drama", true) -> "#287A73"
        genre.contains("Adventure", true) -> "#3D7B55"
        else -> "#56616B"
    }

    private fun genreIcon(genre: String): String = when {
        genre.contains("Comedy", true) -> "🎙"
        genre.contains("Mystery", true) || genre.contains("Suspense", true) -> "🔍"
        genre.contains("Crime", true) || genre.contains("Detective", true) -> "🕵"
        genre.contains("Western", true) -> "🤠"
        genre.contains("Sci-Fi", true) || genre.contains("Horror", true) -> "🚀"
        genre.contains("Drama", true) -> "🎭"
        genre.contains("Adventure", true) -> "🧭"
        else -> "📻"
    }
}
