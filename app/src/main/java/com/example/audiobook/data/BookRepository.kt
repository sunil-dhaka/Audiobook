package com.example.audiobook.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class BookRepository(private val context: Context) {

    private val _books = MutableStateFlow<List<Audiobook>>(emptyList())
    val books: StateFlow<List<Audiobook>> = _books.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    suspend fun scanFolder(folderUri: Uri) = withContext(Dispatchers.IO) {
        _isScanning.value = true
        try {
            val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext
            val files = tree.listFiles().filter { it.isFile }

            val coverByBase: Map<String, Uri> = files.mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                val lower = name.lowercase()
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                    return@mapNotNull null
                }
                stripExtension(name).lowercase() to doc.uri
            }.toMap()

            val results = mutableListOf<Audiobook>()
            files.forEach { doc ->
                val name = doc.name ?: return@forEach
                val lower = name.lowercase()
                if (!(lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".mp3"))) {
                    return@forEach
                }
                val sidecar = coverByBase[stripExtension(name).lowercase()]
                runCatching { buildBook(doc.uri, name, sidecar) }
                    .onSuccess { results += it }
                    .onFailure { Log.w(TAG, "skipping $name", it) }
            }
            _books.value = results.sortedBy { it.title.lowercase() }
        } finally {
            _isScanning.value = false
        }
    }

    private fun buildBook(uri: Uri, displayName: String, sidecarCoverUri: Uri?): Audiobook {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: stripExtension(displayName)
            val author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val cover = mmr.embeddedPicture ?: readSidecarCover(sidecarCoverUri)
            val chapters = ChapterExtractor.extract(context, uri)
            return Audiobook(
                id = uri.toString(),
                uri = uri,
                displayName = displayName,
                title = title,
                author = author,
                durationMs = durationMs,
                coverBytes = cover,
                chapters = chapters,
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot).replace('_', ' ') else name
    }

    private fun readSidecarCover(uri: Uri?): ByteArray? {
        if (uri == null) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    fun getBook(id: String): Audiobook? = _books.value.firstOrNull { it.id == id }

    companion object {
        private const val TAG = "BookRepository"
    }
}
