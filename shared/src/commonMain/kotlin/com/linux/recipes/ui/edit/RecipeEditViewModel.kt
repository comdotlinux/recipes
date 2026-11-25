package com.linux.recipes.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linux.recipes.data.NewImage
import com.linux.recipes.data.RecipeInput
import com.linux.recipes.data.RecipeRepository
import com.linux.recipes.data.SaveResult
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

sealed interface EditStatus {
    data object Editing : EditStatus
    data object Invalid : EditStatus
    data object Saving : EditStatus
    data class Saved(val id: String) : EditStatus
    data object Conflict : EditStatus
    data object Offline : EditStatus
    data class Rejected(val code: String) : EditStatus
    data object Reloading : EditStatus
    /** Reload after a conflict found no recipe: it was deleted elsewhere; the next save creates it again. */
    data object DeletedElsewhere : EditStatus
}

/**
 * Creates a recipe when [recipeId] is null, otherwise edits the cached recipe with that id.
 * With a [draft] (an AI import to review), the form starts from the draft and saving creates it.
 */
class RecipeEditViewModel(
    private val repository: RecipeRepository,
    recipeId: String?,
    defaultLanguage: String,
    draft: RecipeInput? = null,
) : ViewModel() {
    val isNew = recipeId == null
    val isReview = draft != null
    private var expectedRev: Long? = null
    val form = RecipeForm(draft?.id ?: recipeId ?: Uuid.random().toString(), defaultLanguage)
    var status by mutableStateOf<EditStatus>(EditStatus.Editing)
        private set
    /** Errors are shown only after the first save attempt, then kept live. */
    var showErrors by mutableStateOf(false)
        private set
    val errors get() = if (showErrors) form.validate() else emptyMap()
    var tagError by mutableStateOf<FieldError?>(null)
        private set

    init {
        if (recipeId != null) loadLatest(recipeId)
        if (draft != null) form.load(draft)
    }

    /** False when the cache has no recipe with [id]. */
    private fun loadLatest(id: String): Boolean {
        val latest = repository.recipes.value.firstOrNull { it.id == id } ?: return false
        form.load(latest)
        expectedRev = latest.rev
        return true
    }

    fun addTag() {
        tagError = form.addTag()
    }

    fun imagePath(imageId: String): String? = repository.imagePath(imageId)

    fun addImage(bytes: ByteArray) {
        if (form.images.size < Limits.IMAGES) form.images += FormImage(Uuid.random().toString(), bytes)
    }

    /** Counts save taps, so the screen can react to a repeated result. */
    var saveAttempts by mutableStateOf(0)
        private set

    fun save() {
        if (status == EditStatus.Saving) return
        saveAttempts++
        showErrors = true
        if (form.validate().isNotEmpty()) {
            status = EditStatus.Invalid
            return
        }
        status = EditStatus.Saving
        val input = form.toInput()
        val newImages = form.images.mapNotNull { img -> img.bytes?.let { NewImage(img.id, it) } }
        viewModelScope.launch {
            status = when (val result = repository.save(input, expectedRev, newImages)) {
                is SaveResult.Saved -> EditStatus.Saved(result.recipe.id)
                SaveResult.Conflict -> EditStatus.Conflict
                SaveResult.Offline -> EditStatus.Offline
                is SaveResult.Rejected -> EditStatus.Rejected(result.code)
            }
        }
    }

    /** True while the last reload after a conflict could not reach the server; the dialog says so. */
    var reloadOffline by mutableStateOf(false)
        private set

    /**
     * After a conflict: pull the latest version and replace the form with it. Offline, the conflict
     * stays open. When the recipe is gone on the server, the form is kept and the next save creates it
     * again under the same id (the API revives an id that exists only as a deletion marker).
     */
    fun reload() {
        status = EditStatus.Reloading
        viewModelScope.launch {
            if (!repository.sync()) {
                reloadOffline = true
                status = EditStatus.Conflict
                return@launch
            }
            reloadOffline = false
            showErrors = false
            status = if (loadLatest(form.id)) {
                EditStatus.Editing
            } else {
                expectedRev = null
                EditStatus.DeletedElsewhere
            }
        }
    }

    fun dismissResult() {
        reloadOffline = false
        if (status != EditStatus.Saving) status = EditStatus.Editing
    }
}
