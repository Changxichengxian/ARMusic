package com.lalilu.lmusic

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object ARMusicLanguage {
    const val OPTION_CHINESE = 0
    const val OPTION_JAPANESE = 1
    const val OPTION_ENGLISH = 2

    fun savedOption(context: Context): Int {
        return context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
            .getInt(
                Config.KEY_SETTINGS_APP_LANGUAGE_OPTION,
                Config.DEFAULT_SETTINGS_APP_LANGUAGE_OPTION
            )
            .coerceIn(OPTION_CHINESE, OPTION_ENGLISH)
    }

    fun wrapContext(context: Context, option: Int = savedOption(context)): Context {
        val locale = localeForOption(option)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }

        return context.createConfigurationContext(configuration)
    }

    private fun localeForOption(option: Int): Locale {
        return when (option.coerceIn(OPTION_CHINESE, OPTION_ENGLISH)) {
            OPTION_JAPANESE -> Locale.JAPANESE
            OPTION_ENGLISH -> Locale.ENGLISH
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }
}
