package com.shounak.localmeshai.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AppSettingsAutoUnloadRegressionTest {
    @Test fun warmupDefaultsOnAndTheOffPreferenceSurvivesReload() {
        val prefs = createFakePrefs()
        val settings = AppSettings(prefs)
        assertEquals(true, settings.settings.value.warmupAfterLoading)
        settings.updateSettings { it.copy(warmupAfterLoading = false) }
        assertEquals(false, AppSettings(prefs).settings.value.warmupAfterLoading)
        assertEquals(5, AppSettings(prefs).settings.value.autoUnloadMinutes)
    }

    private fun createFakePrefs(initialValues: Map<String, Any?> = emptyMap()): SharedPreferences {
        val map = initialValues.toMutableMap()
        val editorHandler = object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                when (method.name) {
                    "putInt" -> {
                        map[args!![0] as String] = args[1] as Int
                        return proxy
                    }
                    "putBoolean" -> {
                        map[args!![0] as String] = args[1] as Boolean
                        return proxy
                    }
                    "putString" -> {
                        map[args!![0] as String] = args[1] as String?
                        return proxy
                    }
                    "putFloat" -> {
                        map[args!![0] as String] = args[1] as Float
                        return proxy
                    }
                    "apply", "commit" -> return true
                    else -> return proxy
                }
            }
        }
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler
        ) as SharedPreferences.Editor

        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getInt" -> {
                    val key = args!![0] as String
                    val def = args[1] as Int
                    (map[key] as? Int) ?: def
                }
                "getBoolean" -> {
                    val key = args!![0] as String
                    val def = args[1] as Boolean
                    (map[key] as? Boolean) ?: def
                }
                "getString" -> {
                    val key = args!![0] as String
                    val def = args[1] as? String
                    (map[key] as? String) ?: def
                }
                "getFloat" -> {
                    val key = args!![0] as String
                    val def = args[1] as Float
                    (map[key] as? Float) ?: def
                }
                "contains" -> {
                    val key = args!![0] as String
                    map.containsKey(key)
                }
                "edit" -> editor
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            handler
        ) as SharedPreferences
    }

    @Test
    fun defaultAppSettingsDataHas5MinutesAutoUnload() {
        val defaultData = AppSettingsData()
        assertEquals("AppSettingsData default auto-unload should be 5 minutes", 5, defaultData.autoUnloadMinutes)
    }

    @Test
    fun missingPreferenceFallsBackTo5Minutes() {
        val emptyPrefs = createFakePrefs(emptyMap())
        val settings = AppSettings(emptyPrefs)
        assertEquals(
            "Missing auto-unload preference must fall back to 5 minutes",
            5,
            settings.settings.value.autoUnloadMinutes
        )
    }

    @Test
    fun existingSavedPreferenceOf1MinuteIsPreserved() {
        val prefs = createFakePrefs(mapOf(AppSettings.KEY_AUTO_UNLOAD_MINS to 1))
        val settings = AppSettings(prefs)
        assertEquals(
            "Existing saved auto-unload preference of 1 minute must be preserved",
            1,
            settings.settings.value.autoUnloadMinutes
        )
    }

    @Test
    fun existingSavedPreferenceOf0MinutesIsPreserved() {
        val prefs = createFakePrefs(mapOf(AppSettings.KEY_AUTO_UNLOAD_MINS to 0))
        val settings = AppSettings(prefs)
        assertEquals(
            "Existing saved auto-unload preference of 0 (Off) must be preserved",
            0,
            settings.settings.value.autoUnloadMinutes
        )
    }

    @Test
    fun existingSavedPreferenceOf10MinutesIsPreserved() {
        val prefs = createFakePrefs(mapOf(AppSettings.KEY_AUTO_UNLOAD_MINS to 10))
        val settings = AppSettings(prefs)
        assertEquals(
            "Existing saved auto-unload preference of 10 minutes must be preserved",
            10,
            settings.settings.value.autoUnloadMinutes
        )
    }
}
