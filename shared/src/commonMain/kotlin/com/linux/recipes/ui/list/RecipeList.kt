package com.linux.recipes.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import recipes.shared.generated.resources.list_loading
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.ui.components.EmptyPrompt
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.RecipeThumb
import com.linux.recipes.ui.edit.rememberPictureActions
import com.linux.recipes.ui.imports.AiNotice
import com.linux.recipes.ui.imports.ImportPaywall
import com.linux.recipes.ui.imports.ImportSheet
import com.linux.recipes.ui.imports.ImportStatus
import com.linux.recipes.ui.imports.ImportViewModel
import androidx.compose.runtime.LaunchedEffect
import recipes.shared.generated.resources.import_action
import recipes.shared.generated.resources.import_empty_action
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.app_name
import recipes.shared.generated.resources.list_add
import recipes.shared.generated.resources.list_clear_search
import recipes.shared.generated.resources.list_empty_action
import recipes.shared.generated.resources.list_empty_body
import recipes.shared.generated.resources.list_empty_title
import recipes.shared.generated.resources.list_no_matches
import recipes.shared.generated.resources.list_offline
import recipes.shared.generated.resources.list_search_placeholder
import recipes.shared.generated.resources.list_settings
import recipes.shared.generated.resources.minutes_short
import recipes.shared.generated.resources.servings_count

class RecipeListViewModel(private val repository: RecipeRepository) : ViewModel() {
    val recipes = repository.recipes
    var query by mutableStateOf("")
    var refreshing by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    /** False until the first sync has ended; before that an empty cache means "not loaded yet", not "no recipes". */
    var loaded by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            offline = !repository.sync()
            refreshing = false
            loaded = true
        }
    }

    fun imagePath(recipe: Recipe): String? = recipe.imageIds.firstOrNull()?.let(repository::imagePath)
}

/** Title or any tag contains the query, ignoring case and surrounding blanks. */
internal fun List<Recipe>.search(query: String): List<Recipe> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { r -> r.title.contains(q, ignoreCase = true) || r.tags.any { it.contains(q, ignoreCase = true) } }
}

/**
 * @param imports the AI import flow; null hides every import entry point.
 * @param onReview opens the review editor for an import job that has a draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    vm: RecipeListViewModel,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    imports: ImportViewModel? = null,
    onReview: (String) -> Unit = {},
) {
    val all by vm.recipes.collectAsState()
    val job = imports?.activeJob?.collectAsState()?.value
    if (imports != null) {
        LaunchedEffect(imports) { imports.followOpenJob() }
        val pictures = rememberPictureActions(1, onPicked = imports::importPhoto, onFailed = imports::pictureFailed)
        if (imports.sheetOpen) ImportSheet(imports, pictures)
        if (imports.consentFor != null) AiNotice(onAccept = imports::acceptConsent, onNotNow = imports::declineConsent)
        if (imports.paywallOpen) {
            ImportPaywall(
                onTypeIn = {
                    imports.closePaywall()
                    onAdd()
                },
                onClose = imports::closePaywall,
                onSubscribed = imports::subscribed,
            )
        }
    }
    val shown = all.search(vm.query)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(RecipeIcons.Settings, contentDescription = stringResource(Res.string.list_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (all.isNotEmpty()) {
                val buttons: @Composable () -> Unit = {
                    if (imports != null) {
                        // Secondary weight: the tonal colour next to the filled primary.
                        ExtendedFloatingActionButton(
                            onClick = imports::openSheet,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(RecipeIcons.Import, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Text(stringResource(Res.string.import_action))
                        }
                    }
                    // The plain-content overload: the icon+text overload wraps the label in an expand
                    // animation whose text never reaches the Android accessibility tree (TalkBack, UI Automator).
                    ExtendedFloatingActionButton(
                        onClick = onAdd,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(RecipeIcons.Add, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(Res.string.list_add))
                    }
                }
                // At very large text two buttons side by side would run off the screen; they stack instead.
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            // The first load has its own quiet state below; the pull indicator is for later refreshes.
            isRefreshing = vm.refreshing && vm.loaded,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 4.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Only when there is something to show, so an empty item adds no gap.
                if (imports != null && (imports.problem != null || imports.starting || job != null)) {
                    item(key = "import") {
                        ImportStatus(imports, job, onReview = onReview, onTypeIn = {
                            imports.dismissProblem()
                            onAdd()
                        })
                    }
                }
                if (all.isNotEmpty()) {
                    item(key = "search") { SearchField(vm.query) { vm.query = it } }
                }
                if (vm.offline) {
                    item(key = "offline") { NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.list_offline)) }
                }
                when {
                    all.isEmpty() && !vm.loaded -> item(key = "loading") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 96.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(
                                stringResource(Res.string.list_loading),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    all.isEmpty() -> item(key = "empty") {
                        EmptyPrompt(
                            title = stringResource(Res.string.list_empty_title),
                            body = stringResource(Res.string.list_empty_body),
                            actionLabel = stringResource(Res.string.list_empty_action),
                            actionIcon = RecipeIcons.Add,
                            onAction = onAdd,
                            withMark = true,
                            secondaryLabel = imports?.let { stringResource(Res.string.import_empty_action) },
                            secondaryIcon = RecipeIcons.Import,
                            onSecondary = { imports?.openSheet() },
                            modifier = Modifier.padding(top = 56.dp),
                        )
                    }
                    shown.isEmpty() -> item(key = "nomatch") {
                        Text(
                            stringResource(Res.string.list_no_matches, vm.query.trim()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }
                    else -> items(shown, key = { it.id }) { recipe ->
                        RecipeRow(recipe, vm.imagePath(recipe)) { onOpen(recipe.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        placeholder = { Text(stringResource(Res.string.list_search_placeholder)) },
        leadingIcon = { Icon(RecipeIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(RecipeIcons.Close, contentDescription = stringResource(Res.string.list_clear_search))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun RecipeRow(recipe: Recipe, imagePath: String?, onClick: () -> Unit) {
    val meta = recipeMeta(recipe)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // At very large text the thumbnail would squeeze titles into mid-word breaks; text wins.
        if (LocalDensity.current.fontScale < 1.5f) RecipeThumb(recipe.title, imagePath, size = 72.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                recipe.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recipe.tags.isNotEmpty()) {
                Text(
                    recipe.tags.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "45 min · 4 servings": total time and servings, whichever are known. */
@Composable
internal fun recipeMeta(recipe: Recipe): String {
    val total = listOfNotNull(recipe.prepMinutes, recipe.cookMinutes).takeIf { it.isNotEmpty() }?.sum()
    return listOfNotNull(
        total?.let { stringResource(Res.string.minutes_short, it) },
        recipe.servings?.let { pluralStringResource(Res.plurals.servings_count, it, it) },
    ).joinToString("  ·  ")
}
