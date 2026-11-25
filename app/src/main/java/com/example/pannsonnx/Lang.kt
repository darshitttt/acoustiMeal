package com.example.pannsonnx

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat

/** UI language (English / German). Predicted tag labels are never translated. */
object Lang {
    /** Context whose resources follow the in-app language choice (needed in the service on Android < 13). */
    fun context(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locales[0])
        return base.createConfigurationContext(cfg)
    }

    fun isGerman(ctx: Context): Boolean =
        ConfigurationCompat.getLocales(ctx.resources.configuration)[0]?.language == "de"

    /** Switches EN <-> DE; AppCompat recreates the activity and remembers the choice. */
    fun toggle(ctx: Context) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (isGerman(ctx)) "en" else "de"))
    }
}
