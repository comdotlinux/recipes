package com.linux.recipes.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.linux.recipes.data.Ingredient
import com.linux.recipes.data.Recipe
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.EmptyPrompt
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.SectionTitle
import com.linux.recipes.ui.translate.LanguageAction
import com.linux.recipes.ui.translate.TranslateViewModel
import com.linux.recipes.ui.translate.TranslationHeader
import com.linux.recipes.ui.translate.TranslationOverlays
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_back
import recipes.shared.generated.resources.action_cancel
import recipes.shared.generated.resources.detail_cook
import recipes.shared.generated.resources.detail_delete
import recipes.shared.generated.resources.detail_delete_body
import recipes.shared.generated.resources.detail_delete_confirm
import recipes.shared.generated.resources.detail_delete_failed
import recipes.shared.generated.resources.detail_delete_title
import recipes.shared.generated.resources.detail_edit
import recipes.shared.generated.resources.detail_ingredients
import recipes.shared.generated.resources.detail_missing
import recipes.shared.generated.resources.detail_picture
import recipes.shared.generated.resources.detail_picture_pending
import recipes.shared.generated.resources.detail_prep
import recipes.shared.generated.resources.detail_serves
import recipes.shared.generated.resources.detail_source
import recipes.shared.generated.resources.detail_steps
import recipes.shared.generated.resources.detail_tags
import recipes.shared.generated.resources.detail_unfinished
import recipes.shared.generated.resources.detail_unfinished_action
import recipes.shared.generated.resources.minutes_short

class RecipeDetailViewModel(private val repository: RecipeRepository, val id: String) : ViewModel() {
    val recipes = repository.recipes
    var deleting by mutableStateOf(false)
        private set

    fun imagePath(imageId: String): String? = repository.imagePath(imageId)

    /** Calls [onResult] with true once the recipe is gone. */
    fun delete(onResult: (Boolean) -> Unit) {
        if (deleting) return
        deleting = true
        viewModelScope.launch {
            val ok = repository.delete(id)
            deleting = false
            onResult(ok)
        }
    }
}

/** Ingredients by group in order of first appearance; the ungrouped ones keep a null key. */
internal fun List<Ingredient>.grouped(): List<Pair<String?, List<Ingredient>>> =
    groupBy { it.group?.trim()?.ifEmpty { null } }.toList()

/** [translate] null hides the language action (no AI repositories, as in tests of the plain screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(vm: RecipeDetailViewModel, onBack: () -> Unit, onEdit: () -> Unit, translate: TranslateViewModel? = null) {
    val recipes by vm.recipes.collectAsState()
    val recipe = recipes.firstOrNull { it.id == vm.id }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val deleteFailed = stringResource(Res.string.detail_delete_failed)
    var failed by remember { mutableStateOf(0) }
    LaunchedEffect(failed) { if (failed > 0) snackbar.showSnackbar(deleteFailed) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(RecipeIcons.Back, stringResource(Res.string.action_back)) }
                },
                actions = {
                    if (recipe != null) {
                        translate?.let { LanguageAction(it) }
                        IconButton(onClick = onEdit) { Icon(RecipeIcons.Edit, stringResource(Res.string.detail_edit)) }
                        IconButton(onClick = { confirmDelete = true }, enabled = !vm.deleting) {
                            Icon(RecipeIcons.Delete, stringResource(Res.string.detail_delete))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        when {
            recipe != null -> RecipeBody(translate?.shown(recipe) ?: recipe, vm::imagePath, onEdit, Modifier.padding(padding)) {
                translate?.let { TranslationHeader(it, recipe) }
            }
            !gone -> Box(Modifier.fillMaxSize().padding(padding).padding(PageGutter)) {
                NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.detail_missing))
            }
        }
    }

    if (translate != null && recipe != null) TranslationOverlays(translate, recipe)
    if (confirmDelete && recipe != null) {
        ConfirmDialog(
            title = stringResource(Res.string.detail_delete_title),
            body = stringResource(Res.string.detail_delete_body, recipe.title),
            confirm = stringResource(Res.string.detail_delete_confirm),
            dismiss = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                gone = true
                vm.delete { ok ->
                    if (ok) onBack() else {
                        gone = false
                        failed++
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeBody(
    recipe: Recipe,
    imagePath: (String) -> String?,
    onEdit: () -> Unit,
    modifier: Modifier,
    afterTitle: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (recipe.imageIds.isNotEmpty()) item(key = "pictures") { Pictures(recipe.imageIds, imagePath) }
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
                if (recipe.description.isNotBlank()) {
                    Text(
                        recipe.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                afterTitle()
            }
        }
        val facts = listOfNotNull(
            recipe.servings?.let { Res.string.detail_serves to it.toString() },
            recipe.prepMinutes?.let { Res.string.detail_prep to it },
            recipe.cookMinutes?.let { Res.string.detail_cook to it },
        )
        if (facts.isNotEmpty()) item(key = "facts") {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(vertical = 16.dp),
            ) {
                facts.forEachIndexed { i, (label, value) ->
                    if (i > 0) Box(Modifier.width(1.dp).heightIn(min = 44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (value is Int) stringResource(Res.string.minutes_short, value) else value.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (recipe.ingredients.isEmpty() && recipe.steps.isEmpty()) item(key = "unfinished") {
            EmptyPrompt(
                body = stringResource(Res.string.detail_unfinished),
                actionLabel = stringResource(Res.string.detail_unfinished_action),
                actionIcon = RecipeIcons.Edit,
                onAction = onEdit,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        if (recipe.ingredients.isNotEmpty()) item(key = "ingredients") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(Res.string.detail_ingredients))
                recipe.ingredients.grouped().forEach { (group, items) ->
                    if (group != null) {
                        Text(
                            group,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items.forEach { IngredientLine(it) }
                }
            }
        }
        if (recipe.steps.isNotEmpty()) item(key = "steps") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionTitle(stringResource(Res.string.detail_steps))
                recipe.steps.forEachIndexed { i, step -> StepLine(i + 1, step) }
            }
        }
        if (recipe.tags.isNotEmpty()) item(key = "tags") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(Res.string.detail_tags))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipe.tags.forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        recipe.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
            item(key = "source") { Source(url) }
        }
    }
}

@Composable
private fun IngredientLine(ingredient: Ingredient) {
    val amount = listOf(ingredient.quantity, ingredient.unit).filter { it.isNotBlank() }.joinToString(" ")
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                amount,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(88.dp),
            )
            Text(ingredient.item, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 6.dp))
    }
}

@Composable
private fun Source(url: String) {
    val uriHandler = LocalUriHandler.current
    val isLink = url.startsWith("https://") || url.startsWith("http://")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.detail_source))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                .clickable(enabled = isLink) { uriHandler.openUri(url) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (isLink) Icon(RecipeIcons.External, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pictures(imageIds: List<String>, imagePath: (String) -> String?) {
    val pager = rememberPagerState { imageIds.size }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp) { page ->
            val path = imagePath(imageIds[page])
            val label = stringResource(Res.string.detail_picture, page + 1, imageIds.size)
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (path != null) {
                    AsyncImage(model = path, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(RecipeIcons.CloudPending, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Text(
                            stringResource(Res.string.detail_picture_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (imageIds.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(imageIds.size) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                            .background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
    }
}
