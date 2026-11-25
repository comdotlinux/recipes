package com.linux.recipes.ui.settings

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(null, text))
