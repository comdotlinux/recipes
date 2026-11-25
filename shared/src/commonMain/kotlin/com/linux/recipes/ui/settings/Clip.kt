package com.linux.recipes.ui.settings

import androidx.compose.ui.platform.ClipEntry

/** A clipboard entry holding plain text; Compose has no common constructor for one. */
expect fun plainTextClip(text: String): ClipEntry
