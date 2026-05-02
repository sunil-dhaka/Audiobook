package com.example.audiobook

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.audiobook.data.BookRepository

val Application.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "audiobook_prefs")

class AudiobookApp : Application() {

    val bookRepository: BookRepository by lazy { BookRepository(this) }

    companion object {
        lateinit var instance: AudiobookApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
