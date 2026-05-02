package com.example.audiobook.data

import android.net.Uri

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
)

data class Audiobook(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val title: String,
    val author: String?,
    val durationMs: Long,
    val coverBytes: ByteArray?,
    val chapters: List<Chapter>,
) {
    override fun equals(other: Any?): Boolean = other is Audiobook && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
