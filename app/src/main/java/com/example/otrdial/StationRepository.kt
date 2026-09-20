package com.example.otrdial

import android.content.Context
import org.json.JSONArray

object StationRepository {
    fun load(context: Context): List<Station> {
        val json = context.assets.open("stations.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    Station(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        network = o.getString("network"),
                        genre = o.getString("genre"),
                        streamUrl = o.getString("streamUrl"),
                        homepage = o.optString("homepage"),
                        scheduleUrl = o.optString("scheduleUrl"),
                        recordable = o.optBoolean("recordable", true),
                        verification = o.optString("verification")
                    )
                )
            }
        }
    }
}
