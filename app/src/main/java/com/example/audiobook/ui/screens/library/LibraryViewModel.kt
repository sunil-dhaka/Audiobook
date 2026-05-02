package com.example.audiobook.ui.screens.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiobook.data.BookRepository
import com.example.audiobook.data.PrefsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    val repo = (app as com.example.audiobook.AudiobookApp).bookRepository
    private val prefs = PrefsRepository(app)

    val books: StateFlow<List<com.example.audiobook.data.Audiobook>> = repo.books.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val isScanning = repo.isScanning.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    val folderUriFlow = prefs.folderUriFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    init {
        viewModelScope.launch {
            val saved = prefs.folderUri()
            if (saved != null) {
                runCatching { repo.scanFolder(Uri.parse(saved)) }
            }
        }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
            prefs.setFolderUri(uri.toString())
            repo.scanFolder(uri)
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val saved = prefs.folderUri() ?: return@launch
            repo.scanFolder(Uri.parse(saved))
        }
    }
}
