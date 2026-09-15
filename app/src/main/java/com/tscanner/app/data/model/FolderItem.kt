package com.tscanner.app.data.model

import java.io.Serializable

data class FolderItem(
    val id: String,
    var name: String,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable
