package com.lalilu.lmusic.wishlist

import android.app.Application
import com.lalilu.lmusic.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One process-wide serialization point for UI edits and external sync imports. */
class ARMusicWishlistStore(
    application: Application,
) {
    private val preferences = application.getSharedPreferences(
        application.packageName,
        Application.MODE_PRIVATE,
    )
    private val _categoriesJson = MutableStateFlow(readPreference())
    val categoriesJson: StateFlow<String> = _categoriesJson

    fun readRaw(): String = synchronized(mutationLock) {
        readPreference().also { _categoriesJson.value = it }
    }

    fun replaceRaw(value: String): String = synchronized(mutationLock) {
        require(
            preferences.edit()
                .putString(Config.KEY_SETTINGS_WISHLIST_CATEGORIES_JSON, value)
                .commit()
        ) { "愿望单无法安全写入手机设置" }
        val persisted = readPreference()
        check(persisted == value) { "愿望单写入后复核失败" }
        _categoriesJson.value = persisted
        persisted
    }

    /**
     * Saves a UI snapshot only when no agent import has changed the list since that UI rendered.
     * A rejected write refreshes the StateFlow so Compose immediately shows the newer safe copy.
     */
    fun replaceRawIfUnchanged(expectedValue: String, value: String): Boolean = synchronized(mutationLock) {
        val current = readPreference()
        if (current != expectedValue) {
            _categoriesJson.value = current
            return@synchronized false
        }
        require(
            preferences.edit()
                .putString(Config.KEY_SETTINGS_WISHLIST_CATEGORIES_JSON, value)
                .commit()
        ) { "愿望单无法安全写入手机设置" }
        val persisted = readPreference()
        check(persisted == value) { "愿望单写入后复核失败" }
        _categoriesJson.value = persisted
        true
    }

    fun <T> mutateRaw(block: (String) -> Pair<String, T>): T = synchronized(mutationLock) {
        val (next, result) = block(readPreference())
        require(
            preferences.edit()
                .putString(Config.KEY_SETTINGS_WISHLIST_CATEGORIES_JSON, next)
                .commit()
        ) { "愿望单无法安全写入手机设置" }
        val persisted = readPreference()
        check(persisted == next) { "愿望单写入后复核失败" }
        _categoriesJson.value = persisted
        result
    }

    private fun readPreference(): String = preferences
        .getString(Config.KEY_SETTINGS_WISHLIST_CATEGORIES_JSON, "")
        .orEmpty()

    private companion object {
        val mutationLock = Any()
    }
}
