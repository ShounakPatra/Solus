package com.shounak.localmeshai.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesFormatterTest {

    private val sampleV3ReleaseNotes = """
<div align="center">
<h1>Solus <strong>v3.0.0</strong></h1>
<h3>16 KB Device Alignment · 46 MB Optimized Footprint · Pure Offline Zero-Telemetry · Compressed ARM64 Runtime</h3>
<p>
  <a href="https://github.com/ShounakPatra/Solus/releases/download/v3.0.0/app-release.apk" title="Download Solus APK">
    <img src="https://raw.githubusercontent.com/ShounakPatra/Solus/main/docs/assets/download-solus-apk.svg?v=3.0.0" alt="Download Solus APK — Latest v3.0.0" width="360" height="72">
  </a>
</p>
<p>
  <sub>Get the newest APK · Android 9.0+ · Free · 100% Offline</sub>
</p>
<p>
<a href="https://github.com/ShounakPatra/Solus/releases/download/v3.0.0/app-release.apk"><img src="https://img.shields.io/github/v/release/ShounakPatra/Solus?style=for-the-badge&label=version&color=20C997" alt="Latest version"></a>
<a href="https://github.com/ShounakPatra/Solus/releases/download/v3.0.0/app-release.apk"><img src="https://img.shields.io/badge/APK_size-46_MB-22C55E?style=for-the-badge&logo=android&logoColor=white" alt="APK size"></a>
</p>
<p><strong>versionCode <code>7</code> · versionName <code>3.0.0</code> · APK Size <code>46 MB</code> · Kotlin <code>2.3.2</code></strong></p>
</div>

<hr>

<h2>🌟 Highlights</h2>

<table>
<thead>
<tr>
<th>Feature</th>
<th>Description</th>
</tr>
</thead>
<tbody>
<tr>
<td>🚀 <strong>16 KB Page-Size Compliance</strong></td>
<td>Full native ELF 16 KB / 64 KB alignment across all shared libraries (<code>llama.cpp</code>, <code>LiteRT-LM</code>, <code>MediaPipe</code>). Guarantees zero crashes on modern Android 15/16 devices with 16 KB memory pages.</td>
</tr>
<tr>
<td>📉 <strong>Massive 81% Size Reduction</strong></td>
<td>Release APK reduced from 244 MB down to <strong>46 MB</strong> through aggressive R8 minification, resource shrinking, emulator ABI stripping, and compressed native library packaging.</td>
</tr>
<tr>
<td>🛡️ <strong>Pure Offline Zero-Telemetry</strong></td>
<td>Purged all legacy Firebase Crashlytics, installation tracking, and Google Play Services analytics connectors from dependencies and manifests. 100% private, on-device local crash logging.</td>
</tr>
</tbody>
</table>

<hr>

<h2>🚀 What's New & Improved</h2>

<h3>🚀 16 KB Native Memory Page Support</h3>
<ul>
<li>Eliminated the outdated 4 KB-aligned <code>libtensorflowlite_jni.so</code> runtime in favor of modern, 16 KB-compliant LiteRT-LM.</li>
<li>Added <code>-Wl,-z,max-page-size=16384</code> linker options to CMake for all native binaries.</li>
</ul>

<hr>

<h2>🔍 Verification</h2>

```text
versionName = 3.0.0
versionCode = 7
Kotlin      = 2.3.2
```
""".trimIndent()

    @Test
    fun parsesHtmlReleaseNotesWithoutLeakingRawHtmlOrTags() {
        val parsed = UpdateNotesFormatter.parse(sampleV3ReleaseNotes)

        val subtitle = parsed.subtitle
        assertNotNull(subtitle)
        assertTrue(subtitle!!.contains("16 KB Device Alignment"))
        assertFalse(subtitle.contains("<h3"))
        assertFalse(subtitle.contains("</h3>"))

        // Must extract highlights from table
        assertTrue("Highlights should not be empty", parsed.highlights.isNotEmpty())
        assertEquals(3, parsed.highlights.size)

        val firstHighlight = parsed.highlights[0]
        assertTrue(firstHighlight.contains("16 KB Page-Size Compliance"))
        assertTrue(firstHighlight.contains("Full native ELF 16 KB"))
        assertFalse(firstHighlight.contains("<td>"))
        assertFalse(firstHighlight.contains("</td>"))
        assertFalse(firstHighlight.contains("<strong>"))

        // Clean summary must not contain raw HTML tags
        assertFalse(parsed.cleanSummary.contains("<div"))
        assertFalse(parsed.cleanSummary.contains("</div>"))
        assertFalse(parsed.cleanSummary.contains("<h1"))
        assertFalse(parsed.cleanSummary.contains("<p"))
        assertFalse(parsed.cleanSummary.contains("<a href"))
        assertFalse(parsed.cleanSummary.contains("<img"))
        assertFalse(parsed.cleanSummary.contains("title=\"Download Solus APK\""))

        // Full changelog must not contain raw HTML
        assertFalse(parsed.fullChangelog.contains("<div"))
        assertFalse(parsed.fullChangelog.contains("<table"))
        assertFalse(parsed.fullChangelog.contains("</table>"))
        assertFalse(parsed.fullChangelog.contains("versionName = 3.0.0"))
    }

    @Test
    fun formatsNotificationTextCleanly() {
        val notificationText = UpdateNotesFormatter.formatForNotification(sampleV3ReleaseNotes)

        assertFalse(notificationText.contains("<div"))
        assertFalse(notificationText.contains("<h1>"))
        assertFalse(notificationText.contains("href="))
        assertFalse(notificationText.contains("title="))
        assertTrue(notificationText.contains("16 KB Page-Size Compliance"))
        assertTrue(notificationText.startsWith("•"))
    }

    @Test
    fun parsesStandardMarkdownReleaseNotes() {
        val md = """
            # Solus v3.1.0
            
            - Added dark theme toggle
            - Fixed memory leak in audio playback
            - Upgraded llama.cpp backend
        """.trimIndent()

        val parsed = UpdateNotesFormatter.parse(md)
        assertEquals(3, parsed.highlights.size)
        assertTrue(parsed.highlights.any { it.contains("dark theme toggle") })
        assertTrue(parsed.cleanSummary.contains("dark theme toggle"))
    }
}
