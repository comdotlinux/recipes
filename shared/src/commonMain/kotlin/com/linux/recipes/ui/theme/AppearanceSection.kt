package com.linux.recipes.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.SectionTitle
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.appearance_colours
import recipes.shared.generated.resources.appearance_mode
import recipes.shared.generated.resources.appearance_mode_dark
import recipes.shared.generated.resources.appearance_mode_light
import recipes.shared.generated.resources.appearance_mode_system
import recipes.shared.generated.resources.settings_appearance

private val MODES = listOf(
    ThemeMode.System to Res.string.appearance_mode_system,
    ThemeMode.Light to Res.string.appearance_mode_light,
    ThemeMode.Dark to Res.string.appearance_mode_dark,
)

/**
 * Settings, "Appearance": one swatch per palette (its name below, the current one marked) and the mode. A choice
 * applies at once through [store]. Each swatch is one selectable node whose label is the palette's name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(store: AppearanceStore, modifier: Modifier = Modifier) {
    val current by store.state.collectAsState()
    // Each swatch shows its palette in the mode on screen now.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SectionTitle(stringResource(Res.string.settings_appearance), modifier.padding(top = 16.dp))
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.appearance_colours), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Palettes.all.forEach { palette ->
                val selected = palette.id == current.palette
                val scheme = if (dark) palette.dark else palette.light
                Column(
                    Modifier.widthIn(min = 64.dp).clip(MaterialTheme.shapes.medium)
                        .selectable(selected = selected, role = Role.RadioButton) { store.set(current.copy(palette = palette.id)) }
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(scheme.primary)
                            .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(RecipeIcons.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(24.dp))
                    }
                    Text(stringResource(palette.name), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        }
        Text(stringResource(Res.string.appearance_mode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.selectableGroup()) {
            MODES.forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = current.mode == mode, role = Role.RadioButton) { store.set(current.copy(mode = mode)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current.mode == mode, onClick = null)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
