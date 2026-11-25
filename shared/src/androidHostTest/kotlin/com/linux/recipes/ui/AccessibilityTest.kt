package com.linux.recipes.ui

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.ui.detail.RecipeDetailScreen
import com.linux.recipes.ui.detail.RecipeDetailViewModel
import com.linux.recipes.ui.edit.RecipeEditScreen
import com.linux.recipes.ui.edit.RecipeEditViewModel
import com.linux.recipes.data.ImportJobState
import com.linux.recipes.data.ImportOutcome
import com.linux.recipes.ui.imports.AiNoticeContent
import com.linux.recipes.ui.imports.ImportPaywallContent
import com.linux.recipes.ui.paywall.PaywallContent
import com.linux.recipes.ui.imports.ImportSheetContent
import com.linux.recipes.ui.imports.ImportViewModel
import com.linux.recipes.ui.imports.ReportContent
import com.linux.recipes.ui.imports.ReviewImportScreen
import com.linux.recipes.ui.list.RecipeListScreen
import com.linux.recipes.ui.list.RecipeListViewModel
import com.linux.recipes.ui.settings.SettingsScreen
import com.linux.recipes.ui.settings.SettingsViewModel
import com.linux.recipes.ui.signin.SignInScreen
import com.linux.recipes.ui.signin.SignInViewModel
import com.linux.recipes.ui.theme.RecipesTheme
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks what TalkBack and UI Automator see: the Android AccessibilityNodeInfo that Compose builds for
 * each semantics node, not only the Compose semantics tree, which can differ.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTest : UiTest() {
    private class Clickable(val label: String, val info: AccessibilityNodeInfo)

    /** Every clickable node on screen with the text or description the platform exposes for it. */
    private fun ComposeUiTest.clickables(view: View): List<Clickable> {
        val provider = assertNotNull(view.accessibilityNodeProvider, "Compose exposes no accessibility provider")
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            nodes += n
            n.children.forEach(::walk)
        }
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        // Compose uses the semantics id as the virtual view id; the root is the host view.
        fun info(node: SemanticsNode) = provider.createAccessibilityNodeInfo(if (node.isRoot) View.NO_ID else node.id)
        // A screen reader speaks a clickable node's own text and description, or else those of its descendants.
        fun label(node: SemanticsNode): String {
            val own = info(node)?.let { listOfNotNull(it.text, it.contentDescription).joinToString(" ").trim() }.orEmpty()
            return own.ifEmpty { node.children.map(::label).filter { it.isNotEmpty() }.joinToString(" ") }
        }
        return nodes.filter { SemanticsActions.OnClick in it.config }.mapNotNull { node ->
            info(node)?.let { Clickable(label(node), it) }
        }
    }

    private fun screen(content: @Composable () -> Unit, check: ComposeUiTest.(List<Clickable>) -> Unit) = runComposeUiTest {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        lateinit var view: View
        setContent {
            view = LocalView.current
            RecipesTheme(darkTheme = false) { content() }
        }
        waitForIdle()
        check(clickables(view))
    }

    private fun everyClickableIsLabelled(content: @Composable () -> Unit) = screen(content) { found ->
        assertTrue(found.isNotEmpty(), "screen has clickable nodes")
        val unlabelled = found.filter { it.label.isEmpty() }.map { it.info.className }
        assertTrue(unlabelled.isEmpty(), "clickable nodes without text or description: $unlabelled")
    }

    @Test
    fun newRecipeButtonIsExposedWithItsLabelWhenRecipesExist() = screen({
        RecipeListScreen(remember { RecipeListViewModel(FakeRecipeRepository(listOf(shakshuka))) }, {}, {}, {})
    }) { found ->
        val button = found.singleOrNull { it.label == "New recipe" }
        assertNotNull(button, "found: ${found.map { it.label }}")
        assertTrue(button.info.isClickable)
        assertTrue(button.info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        assertEquals(true, button.info.isEnabled)
    }

    @Test
    fun signInClickablesAreLabelled() = everyClickableIsLabelled { SignInScreen(remember { SignInViewModel(FakeSessionRepository()) }) }

    @Test
    fun listClickablesAreLabelled() = everyClickableIsLabelled {
        RecipeListScreen(remember { RecipeListViewModel(FakeRecipeRepository(listOf(shakshuka, potatoSalad))) }, {}, {}, {})
    }

    @Test
    fun emptyListClickablesAreLabelled() = everyClickableIsLabelled {
        RecipeListScreen(remember { RecipeListViewModel(FakeRecipeRepository()) }, {}, {}, {})
    }

    @Test
    fun detailClickablesAreLabelled() = everyClickableIsLabelled {
        RecipeDetailScreen(remember { RecipeDetailViewModel(FakeRecipeRepository(listOf(potatoSalad)), potatoSalad.id) }, {}, {})
    }

    @Test
    fun editorClickablesAreLabelled() = everyClickableIsLabelled {
        RecipeEditScreen(remember { RecipeEditViewModel(FakeRecipeRepository(listOf(potatoSalad)), potatoSalad.id, "en") }, {}, {})
    }

    @Test
    fun settingsClickablesAreLabelled() = everyClickableIsLabelled {
        SettingsScreen(remember { SettingsViewModel(FakeSessionRepository(signedIn = true), FakeConsentRepository()) }, {})
    }

    // ---------- M2: AI import. Sheets and dialogs open in their own window, so their content is checked here. ----------

    private fun listWithJob(job: ImportJobState?, recipes: FakeRecipeRepository = FakeRecipeRepository(listOf(shakshuka))) =
        everyClickableIsLabelled {
            RecipeListScreen(
                remember { RecipeListViewModel(recipes) }, {}, {}, {},
                imports = remember { ImportViewModel(FakeImportRepository(job), FakeConsentRepository()) },
            )
        }

    @Test
    fun importButtonsAreLabelled() = screen({
        RecipeListScreen(
            remember { RecipeListViewModel(FakeRecipeRepository(listOf(shakshuka))) }, {}, {}, {},
            imports = remember { ImportViewModel(FakeImportRepository(), FakeConsentRepository()) },
        )
    }) { found ->
        assertNotNull(found.singleOrNull { it.label == "Import" }, "found: ${found.map { it.label }}")
        assertNotNull(found.singleOrNull { it.label == "New recipe" }, "found: ${found.map { it.label }}")
    }

    @Test
    fun emptyListImportIsLabelled() = listWithJob(null, FakeRecipeRepository())

    @Test
    fun readyCardClickablesAreLabelled() = listWithJob(ImportJobState("j", "url", ImportOutcome.Done(importedDraft), offline = false))

    @Test
    fun failedCardClickablesAreLabelled() = listWithJob(ImportJobState("j", "url", ImportOutcome.Failed, offline = false))

    @Test
    fun importSheetClickablesAreLabelled() = everyClickableIsLabelled {
        ImportSheetContent("https://example.com", {}, firstFree = true, {}, {}, {})
    }

    @Test
    fun aiNoticeClickablesAreLabelled() = everyClickableIsLabelled { AiNoticeContent({}, {}) }

    @Test
    fun paywallClickablesAreLabelled() = everyClickableIsLabelled { ImportPaywallContent({}, {}) }

    // ---------- M3: Recipes Pro ----------

    @Test
    fun paywallWithPlansClickablesAreLabelled() = everyClickableIsLabelled {
        PaywallContent(testPlans, "monthly", {}, working = false, message = null, {}, {}, onTypeIn = {}, onClose = {})
    }

    @Test
    fun settingsWithRecipesProClickablesAreLabelled() = everyClickableIsLabelled {
        SettingsScreen(
            remember { SettingsViewModel(FakeSessionRepository(signedIn = true), FakeConsentRepository(), FakeImportRepository(), FakeBillingRepository()) },
            {},
        )
    }

    @Test
    fun reportClickablesAreLabelled() = everyClickableIsLabelled { ReportContent({ _, _ -> }, {}) }

    @Test
    fun reviewClickablesAreLabelled() = everyClickableIsLabelled {
        ReviewImportScreen(
            remember { RecipeEditViewModel(FakeRecipeRepository(), null, "en", importedDraft) },
            FakeImportRepository(), "j", {}, {},
        )
    }
}
