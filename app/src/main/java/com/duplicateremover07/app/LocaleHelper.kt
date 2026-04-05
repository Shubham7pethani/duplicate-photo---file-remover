package com.duplicateremover07.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LocaleHelper {
    private const val SELECTED_LANGUAGE = "Locale.Helper.Selected.Language"

    fun onAttach(context: Context): Context {
        val lang = getPersistedData(context, Locale.getDefault().language)
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        return context.createConfigurationContext(configuration)
    }

    fun setLocale(context: Context, language: String): Context {
        persist(context, language)
        val locale = Locale(language)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)

        // Update application context as well for global strings
        context.applicationContext.resources.updateConfiguration(configuration, resources.displayMetrics)

        return context.createConfigurationContext(configuration)
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String {
        val preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        return preferences.getString("My_Lang", defaultLanguage) ?: defaultLanguage
    }

    private fun persist(context: Context, language: String) {
        val preferences = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
        preferences.edit().putString("My_Lang", language).apply()
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    private fun updateResourcesLegacy(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val resources = context.resources
        val configuration = resources.configuration
        configuration.locale = locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return context
    }
}
