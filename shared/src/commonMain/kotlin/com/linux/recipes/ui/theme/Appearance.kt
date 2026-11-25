package com.linux.recipes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { System, Light, Dark }

/** The theme the user picked: a [Palettes] id and the light or dark mode. */
@Serializable
data class Appearance(val palette: String = Palettes.Herb.id, val mode: ThemeMode = ThemeMode.System)

/**
 * The appearance as a device preference in [file]. AppGraph puts it at `<filesDir>/appearance.json`, next to the
 * account cache directory (`recipes-data`) rather than inside it: sign-out wipes that directory, never this file,
 * and nothing here is sent to the server. [state] is read once at construction, before the first frame, so the
 * app starts in the chosen theme.
 */
class AppearanceStore(private val file: Path, private val fs: FileSystem = SystemFileSystem) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Appearance> = _state.asStateFlow()

    /** Applies at once; when the file cannot be written, the choice still holds until the app ends. */
    fun set(appearance: Appearance) {
        _state.value = appearance
        try {
            val dir = requireNotNull(file.parent)
            fs.createDirectories(dir)
            val tmp = Path(dir, "${file.name}.tmp")
            fs.sink(tmp).buffered().use { it.write(json.encodeToString(Appearance.serializer(), appearance).encodeToByteArray()) }
            fs.atomicMove(tmp, file)
        } catch (_: IOException) {
            Unit
        }
    }

    private fun read(): Appearance = runCatching {
        val text = fs.source(file).buffered().use { it.readByteArray().decodeToString() }
        json.decodeFromString(Appearance.serializer(), text)
    }.getOrNull() ?: Appearance()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Provided by `App`; null (tests of single screens) hides the Appearance section in Settings. */
val LocalAppearance = staticCompositionLocalOf<AppearanceStore?> { null }

/** Whether this appearance asks for the dark scheme; [ThemeMode.System] follows [systemDark]. */
fun Appearance.isDark(systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** [RecipesTheme] as [store] says, provided as [LocalAppearance]; without a store, Herb and [systemDark]. */
@Composable
fun AppTheme(store: AppearanceStore?, systemDark: Boolean, content: @Composable () -> Unit) {
    val look = store?.state?.collectAsState()?.value ?: Appearance()
    RecipesTheme(look.isDark(systemDark), Palettes.byId(look.palette)) {
        CompositionLocalProvider(LocalAppearance provides store, content = content)
    }
}
