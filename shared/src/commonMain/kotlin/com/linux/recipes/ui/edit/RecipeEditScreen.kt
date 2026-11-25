package com.linux.recipes.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import recipes.shared.generated.resources.unit_minutes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.AsyncImage
import com.linux.recipes.ui.components.ConfirmDialog
import com.linux.recipes.ui.components.NoteBanner
import com.linux.recipes.ui.components.PageGutter
import com.linux.recipes.ui.components.RecipeIcons
import com.linux.recipes.ui.components.SectionTitle
import org.jetbrains.compose.resources.stringResource
import recipes.shared.generated.resources.Res
import recipes.shared.generated.resources.action_close
import recipes.shared.generated.resources.edit_add_group
import recipes.shared.generated.resources.edit_add_ingredient
import recipes.shared.generated.resources.edit_add_step
import recipes.shared.generated.resources.edit_add_tag
import recipes.shared.generated.resources.edit_choose_pictures
import recipes.shared.generated.resources.edit_take_photo
import recipes.shared.generated.resources.edit_conflict_body
import recipes.shared.generated.resources.edit_conflict_keep
import recipes.shared.generated.resources.edit_conflict_offline
import recipes.shared.generated.resources.edit_deleted_elsewhere
import recipes.shared.generated.resources.edit_conflict_reload
import recipes.shared.generated.resources.edit_conflict_title
import recipes.shared.generated.resources.edit_discard_body
import recipes.shared.generated.resources.edit_discard_confirm
import recipes.shared.generated.resources.edit_discard_keep
import recipes.shared.generated.resources.edit_discard_title
import recipes.shared.generated.resources.edit_error_duplicate_tag
import recipes.shared.generated.resources.edit_error_form
import recipes.shared.generated.resources.edit_error_language
import recipes.shared.generated.resources.edit_error_range
import recipes.shared.generated.resources.edit_error_required
import recipes.shared.generated.resources.edit_error_too_long
import recipes.shared.generated.resources.edit_error_too_many
import recipes.shared.generated.resources.edit_field_cook
import recipes.shared.generated.resources.edit_field_description
import recipes.shared.generated.resources.edit_field_group
import recipes.shared.generated.resources.edit_field_item
import recipes.shared.generated.resources.edit_field_language
import recipes.shared.generated.resources.edit_field_prep
import recipes.shared.generated.resources.edit_field_quantity
import recipes.shared.generated.resources.edit_field_servings
import recipes.shared.generated.resources.edit_field_source
import recipes.shared.generated.resources.edit_field_step
import recipes.shared.generated.resources.edit_field_tag
import recipes.shared.generated.resources.edit_field_title
import recipes.shared.generated.resources.edit_field_unit
import recipes.shared.generated.resources.edit_move_down
import recipes.shared.generated.resources.edit_move_up
import recipes.shared.generated.resources.edit_offline
import recipes.shared.generated.resources.edit_picture_failed
import recipes.shared.generated.resources.edit_picture_remove
import recipes.shared.generated.resources.edit_pictures_hint
import recipes.shared.generated.resources.edit_rejected_image_limit
import recipes.shared.generated.resources.edit_rejected_image_too_large
import recipes.shared.generated.resources.edit_rejected_limit
import recipes.shared.generated.resources.edit_rejected_other
import recipes.shared.generated.resources.edit_rejected_recipe_storage
import recipes.shared.generated.resources.edit_rejected_storage
import recipes.shared.generated.resources.edit_rejected_too_large
import recipes.shared.generated.resources.edit_remove_row
import recipes.shared.generated.resources.edit_remove_tag
import recipes.shared.generated.resources.edit_save
import recipes.shared.generated.resources.edit_saving
import recipes.shared.generated.resources.edit_section_basics
import recipes.shared.generated.resources.edit_section_ingredients
import recipes.shared.generated.resources.edit_section_more
import recipes.shared.generated.resources.edit_section_pictures
import recipes.shared.generated.resources.edit_section_steps
import recipes.shared.generated.resources.edit_section_tags
import recipes.shared.generated.resources.edit_title_edit
import recipes.shared.generated.resources.edit_title_new
import recipes.shared.generated.resources.review_discard_body
import recipes.shared.generated.resources.review_discard_title
import recipes.shared.generated.resources.review_title

@Composable
internal fun FieldError.text(): String = when (this) {
    FieldError.Required -> stringResource(Res.string.edit_error_required)
    is FieldError.TooLong -> stringResource(Res.string.edit_error_too_long, max)
    is FieldError.Range -> stringResource(Res.string.edit_error_range, min, max)
    is FieldError.TooMany -> stringResource(Res.string.edit_error_too_many, max)
    FieldError.DuplicateTag -> stringResource(Res.string.edit_error_duplicate_tag)
    FieldError.Language -> stringResource(Res.string.edit_error_language)
}

@Composable
private fun rejectedText(code: String): String = when (code) {
    "recipe_too_large" -> stringResource(Res.string.edit_rejected_too_large)
    "recipe_limit" -> stringResource(Res.string.edit_rejected_limit)
    "image_too_large" -> stringResource(Res.string.edit_rejected_image_too_large)
    "storage_cap" -> stringResource(Res.string.edit_rejected_storage)
    "image_limit" -> stringResource(Res.string.edit_rejected_image_limit)
    "recipe_storage_cap" -> stringResource(Res.string.edit_rejected_recipe_storage)
    // Never show a raw server code to the user.
    else -> stringResource(Res.string.edit_rejected_other)
}

/**
 * @param onSaved called with the recipe id after a successful save.
 * @param onClose leaves the editor without saving; guarded by a discard dialog when the form has changes,
 *   and always when reviewing an import, whose draft is lost on close.
 * @param header shown above the form, below the status line; the import review puts its report action here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    vm: RecipeEditViewModel,
    onSaved: (String) -> Unit,
    onClose: () -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val form = vm.form
    val status = vm.status
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pictureFailed by rememberSaveable { mutableStateOf(false) }
    val saved = status is EditStatus.Saved
    LaunchedEffect(status) { if (status is EditStatus.Saved) onSaved(status.id) }
    val listState = rememberLazyListState()
    // The result banner sits at the top of the form; bring it into view after every failed save.
    LaunchedEffect(status, vm.saveAttempts) {
        if (status == EditStatus.Invalid || status == EditStatus.Offline || status is EditStatus.Rejected ||
            status == EditStatus.DeletedElsewhere
        ) {
            listState.animateScrollToItem(0)
        }
    }

    val guarded = (form.isDirty || vm.isReview) && !saved
    val requestClose = { if (guarded) confirmDiscard = true else onClose() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = guarded,
        onBackCompleted = { confirmDiscard = true },
    )
    val pictures = rememberPictureActions(Limits.IMAGES - form.images.size, onPicked = {
        pictureFailed = false
        vm.addImage(it)
    }, onFailed = { pictureFailed = true })
    val errors = vm.errors
    val titleRes = when {
        vm.isReview -> Res.string.review_title
        vm.isNew -> Res.string.edit_title_new
        else -> Res.string.edit_title_edit
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = requestClose) { Icon(RecipeIcons.Close, stringResource(Res.string.action_close)) }
                },
                actions = {
                    if (status == EditStatus.Saving || status == EditStatus.Reloading) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    }
                    TextButton(
                        onClick = vm::save,
                        enabled = status != EditStatus.Saving && status != EditStatus.Reloading,
                        modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(if (status == EditStatus.Saving) Res.string.edit_saving else Res.string.edit_save),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 4.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "status") {
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    when (status) {
                        EditStatus.Invalid -> NoteBanner(RecipeIcons.Close, stringResource(Res.string.edit_error_form), error = true)
                        EditStatus.Offline -> NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.edit_offline))
                        is EditStatus.Rejected -> NoteBanner(RecipeIcons.Close, rejectedText(status.code), error = true)
                        EditStatus.DeletedElsewhere -> NoteBanner(RecipeIcons.CloudOff, stringResource(Res.string.edit_deleted_elsewhere))
                        else -> if (pictureFailed) NoteBanner(RecipeIcons.Gallery, stringResource(Res.string.edit_picture_failed), error = true)
                    }
                }
            }
            if (header != null) item(key = "header") { header() }
            item(key = "pictures") { PicturesSection(form, pictures, vm::imagePath) }
            item(key = "basics") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(Res.string.edit_section_basics))
                    FormField(form.title, { form.title = it }, stringResource(Res.string.edit_field_title), errors[Fields.TITLE])
                    FormField(
                        form.description, { form.description = it }, stringResource(Res.string.edit_field_description),
                        errors[Fields.DESCRIPTION], singleLine = false,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                        val minutes = stringResource(Res.string.unit_minutes)
                        FormField(form.servings, { form.servings = it }, stringResource(Res.string.edit_field_servings), errors[Fields.SERVINGS], Modifier.weight(1f), keyboard = number)
                        FormField(form.prep, { form.prep = it }, stringResource(Res.string.edit_field_prep), errors[Fields.PREP], Modifier.weight(1f), keyboard = number, suffix = minutes)
                        FormField(form.cook, { form.cook = it }, stringResource(Res.string.edit_field_cook), errors[Fields.COOK], Modifier.weight(1f), keyboard = number, suffix = minutes)
                    }
                }
            }
            item(key = "ingredients-title") {
                Column {
                    SectionTitle(stringResource(Res.string.edit_section_ingredients))
                    errors[Fields.INGREDIENTS]?.let { ErrorLine(it.text()) }
                }
            }
            itemsIndexed(form.ingredients, key = { _, row -> "i${row.key}" }) { index, row ->
                RowCard(
                    index = index,
                    count = form.ingredients.size,
                    onMove = { by -> form.move(form.ingredients, index, by) },
                    onRemove = { form.ingredients.removeAt(index) },
                ) {
                    if (row.isGroup) {
                        FormField(row.item, { row.item = it }, stringResource(Res.string.edit_field_group), errors[Fields.item(row)])
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FormField(row.quantity, { row.quantity = it }, stringResource(Res.string.edit_field_quantity), errors[Fields.quantity(row)], Modifier.weight(1f))
                            FormField(row.unit, { row.unit = it }, stringResource(Res.string.edit_field_unit), errors[Fields.unit(row)], Modifier.weight(1f))
                        }
                        FormField(row.item, { row.item = it }, stringResource(Res.string.edit_field_item), errors[Fields.item(row)])
                    }
                }
            }
            item(key = "ingredients-add") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton(stringResource(Res.string.edit_add_ingredient)) { form.ingredients += IngredientRow(isGroup = false) }
                    AddButton(stringResource(Res.string.edit_add_group)) { form.ingredients += IngredientRow(isGroup = true) }
                }
            }
            item(key = "steps-title") {
                Column {
                    SectionTitle(stringResource(Res.string.edit_section_steps))
                    errors[Fields.STEPS]?.let { ErrorLine(it.text()) }
                }
            }
            itemsIndexed(form.steps, key = { _, row -> "s${row.key}" }) { index, row ->
                RowCard(
                    index = index,
                    count = form.steps.size,
                    onMove = { by -> form.move(form.steps, index, by) },
                    onRemove = { form.steps.removeAt(index) },
                ) {
                    FormField(row.text, { row.text = it }, stringResource(Res.string.edit_field_step, index + 1), errors[Fields.step(row)], singleLine = false)
                }
            }
            item(key = "steps-add") { AddButton(stringResource(Res.string.edit_add_step)) { form.steps += StepRow() } }
            item(key = "tags") { TagsSection(vm) }
            item(key = "more") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(Res.string.edit_section_more))
                    FormField(
                        form.sourceUrl, { form.sourceUrl = it }, stringResource(Res.string.edit_field_source), errors[Fields.SOURCE],
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    FormField(form.language, { form.language = it }, stringResource(Res.string.edit_field_language), errors[Fields.LANGUAGE])
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(if (vm.isReview) Res.string.review_discard_title else Res.string.edit_discard_title),
            body = stringResource(if (vm.isReview) Res.string.review_discard_body else Res.string.edit_discard_body),
            confirm = stringResource(Res.string.edit_discard_confirm),
            dismiss = stringResource(Res.string.edit_discard_keep),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (status == EditStatus.Conflict) {
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(stringResource(Res.string.edit_conflict_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.edit_conflict_body), style = MaterialTheme.typography.bodyLarge)
                    if (vm.reloadOffline) {
                        Text(
                            stringResource(Res.string.edit_conflict_offline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::reload) { Text(stringResource(Res.string.edit_conflict_reload)) } },
            dismissButton = { TextButton(onClick = vm::dismissResult) { Text(stringResource(Res.string.edit_conflict_keep)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: FieldError?,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    suffix: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it.text()) } },
        singleLine = singleLine,
        suffix = suffix?.let { { Text(it) } },
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = keyboard,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
        Icon(RecipeIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A tonal card around one ingredient or step, with move and remove actions along its bottom edge. */
@Composable
private fun RowCard(
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp),
    ) {
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallAction(RecipeIcons.Up, stringResource(Res.string.edit_move_up), enabled = index > 0) { onMove(-1) }
            SmallAction(RecipeIcons.Down, stringResource(Res.string.edit_move_down), enabled = index < count - 1) { onMove(1) }
            SmallAction(RecipeIcons.Delete, stringResource(Res.string.edit_remove_row), onClick = onRemove)
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(vm: RecipeEditViewModel) {
    val form = vm.form
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(Res.string.edit_section_tags))
        if (form.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                form.tags.toList().forEach { tag ->
                    val remove = stringResource(Res.string.edit_remove_tag, tag)
                    InputChip(
                        selected = false,
                        onClick = { form.tags.remove(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Icon(RecipeIcons.Close, contentDescription = remove, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                form.tagDraft, { form.tagDraft = it }, stringResource(Res.string.edit_field_tag),
                vm.tagError ?: vm.errors[Fields.TAG_DRAFT] ?: vm.errors[Fields.TAGS], Modifier.weight(1f),
                keyboard = KeyboardOptions(imeAction = ImeAction.Done),
            )
            OutlinedButton(onClick = vm::addTag, modifier = Modifier.padding(top = 8.dp).heightIn(min = 56.dp), shape = MaterialTheme.shapes.small) {
                Text(stringResource(Res.string.edit_add_tag))
            }
        }
    }
}

@Composable
private fun PicturesSection(form: RecipeForm, actions: PictureActions, existingPath: (String) -> String?) {
    val full = form.images.size >= Limits.IMAGES
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(Res.string.edit_section_pictures))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(form.images, key = { _, img -> img.id }) { i, img ->
                Box(Modifier.size(TILE).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    val model: Any? = img.bytes ?: existingPath(img.id)
                    if (model != null) {
                        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(
                            RecipeIcons.CloudPending, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                        )
                    }
                    IconButton(
                        onClick = { form.images.removeAt(i) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Icon(RecipeIcons.Close, stringResource(Res.string.edit_picture_remove, i + 1), modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!full) {
                item(key = "camera") { AddTile(RecipeIcons.Camera, stringResource(Res.string.edit_take_photo), actions.takePhoto) }
                item(key = "gallery") { AddTile(RecipeIcons.Gallery, stringResource(Res.string.edit_choose_pictures), actions.choose) }
            }
        }
        Text(
            stringResource(Res.string.edit_pictures_hint, Limits.IMAGES),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val TILE = 112.dp

/** An outlined tile that adds pictures; thumbnail-sized, and grows with large text instead of clipping it. */
@Composable
private fun AddTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.defaultMinSize(TILE, TILE).widthIn(max = TILE * 2).clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
