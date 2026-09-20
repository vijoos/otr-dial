package com.example.otrdial

data class Station(
    val id: String,
    val name: String,
    val network: String,
    val genre: String,
    val streamUrl: String,
    val homepage: String,
    val scheduleUrl: String,
    val recordable: Boolean,
    val verification: String
)
