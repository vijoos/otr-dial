package com.example.otrdial

import android.content.Context
import org.json.JSONArray

object StationRepository {
    private fun read(context: Context, file: String): List<Station> = runCatching {
        val array = JSONArray(context.assets.open(file).bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(Station(
                    id = o.getString("id"), name = o.getString("name"), network = o.getString("network"),
                    genre = o.getString("genre"), streamUrl = o.getString("streamUrl"), homepage = o.optString("homepage"),
                    scheduleUrl = o.optString("scheduleUrl"), recordable = o.optBoolean("recordable", true),
                    verification = o.optString("verification")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun load(context: Context): List<Station> =
        (read(context, "stations.json") + read(context, "stations-2.6.json"))
            .distinctBy { it.id }
            .distinctBy { it.streamUrl }
}
