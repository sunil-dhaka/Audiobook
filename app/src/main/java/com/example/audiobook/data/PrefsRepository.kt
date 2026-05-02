package com.example.audiobook.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.audiobook.AudiobookApp
import com.example.audiobook.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class PrefsRepository(private val context: Context = AudiobookApp.instance) {

    private val ds = (context.applicationContext as AudiobookApp).appDataStore

    suspend fun setFolderUri(uri: String?) {
        ds.edit { p ->
            if (uri == null) p.remove(KEY_FOLDER_URI) else p[KEY_FOLDER_URI] = uri
        }
    }

    suspend fun folderUri(): String? = ds.data.first()[KEY_FOLDER_URI]

    fun folderUriFlow(): Flow<String?> = ds.data.map { it[KEY_FOLDER_URI] }

    suspend fun setPosition(bookId: String, ms: Long) {
        ds.edit { it[longPreferencesKey("pos:$bookId")] = ms }
    }

    suspend fun getPosition(bookId: String): Long =
        ds.data.first()[longPreferencesKey("pos:$bookId")] ?: 0L

    suspend fun setSpeed(speed: Float) {
        ds.edit { it[KEY_SPEED] = speed }
    }

    suspend fun getSpeed(): Float =
        ds.data.first()[KEY_SPEED] ?: 1.0f

    fun speedFlow(): Flow<Float> = ds.data.map { it[KEY_SPEED] ?: 1.0f }

    companion object {
        private val KEY_FOLDER_URI = stringPreferencesKey("folder_uri")
        private val KEY_SPEED = floatPreferencesKey("speed")
    }
}
