package com.linux.recipes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.linux.recipes.App
import com.linux.recipes.platform.LocalTurnstileView
import com.linux.recipes.ui.edit.LocalPictureActions
import com.linux.recipes.ui.edit.PictureActions
import com.linux.recipes.ui.signin.TurnstileChallenge
import com.linux.recipes.ui.share.LocalFileActions
import com.linux.recipes.ui.share.LocalSharing
import com.linux.recipes.ui.share.SharingServices
import com.linux.recipes.ui.theme.AppearanceStore

class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) {
        opened += uri
    }
}

/**
 * The only way common UI tests set content: [content] as on a phone, on every platform. The Compose test window on
 * iOS is 1024 x 768 at density 1 (`runComposeUiTest` defaults in compose ui-test
 * `skikoMain/.../v2/ComposeUiTest.skiko.kt`), so a 4:3 picture fills the whole height and lazy lists never compose
 * what follows; Robolectric already runs at 360 x 640 dp (`UiTest`). The picture launchers and the Turnstile web view
 * are replaced by [testDoubles]: iOS cannot host them here. Plain `setContent` belongs only in Android host tests.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setScreen(content: @Composable () -> Unit) {
    setContent {
        Box(Modifier.size(360.dp, 640.dp)) { testDoubles(content) }
    }
}

/** [App] with fake repositories, through [setScreen]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setApp(
    session: FakeSessionRepository,
    recipes: FakeRecipeRepository,
    uriHandler: UriHandler = RecordingUriHandler(),
    darkTheme: Boolean = false,
    imports: FakeImportRepository? = null,
    consent: FakeConsentRepository? = null,
    turnstile: TurnstileChallenge? = null,
    translations: FakeTranslationRepository? = null,
    appearance: AppearanceStore? = null,
    sharing: SharingServices? = null,
) {
    setScreen {
        CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalSharing provides sharing) {
            App(
                session, recipes, darkTheme, turnstile, importRepository = imports, consentRepository = consent,
                translationRepository = translations, appearance = appearance,
            )
        }
    }
}

/**
 * Picture launchers that do nothing, a Turnstile view that shows nothing (the gate is driven by the test) and system
 * dialogs (share sheet, save, open) that do nothing; a test that checks them provides its own [FakeFileActions].
 */
@Composable
fun testDoubles(content: @Composable () -> Unit) = CompositionLocalProvider(
    LocalPictureActions provides PictureActions(takePhoto = {}, choose = {}),
    LocalFileActions provides FakeFileActions().actions,
    LocalTurnstileView provides { _, _, modifier, _ -> Box(modifier) },
    content = content,
)

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) = waitWithTexts("\"$text\" to appear") {
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.waitForNoText(text: String) = waitWithTexts("\"$text\" to disappear") {
    onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
}

/** Like `waitUntil`, but a timeout names every text on screen, so a failure on CI says where the app really was. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.waitWithTexts(what: String, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMillis = 5_000, condition = condition)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("waited for $what; on screen: ${screenTexts()}", e)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.screenTexts(): List<String> {
    val texts = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { texts += "[$it]" }
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).forEach(::walk)
    return texts
}

/** Scrolls the screen's lazy list until a node with [text] is composed and on screen. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.scrollTo(text: String, substring: Boolean = false) {
    val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    onNode(hasScrollToNodeAction() and vertical).performScrollToNode(hasText(text, substring = substring))
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.click(text: String) = onNodeWithText(text).performClick()

/** Types into the text field labelled [label], replacing what is there. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.type(label: String, value: String) {
    scrollTo(label)
    onNodeWithText(label).performTextReplacement(value)
}

/** Clicks the last node with [text]; a dialog's button when the screen behind shows the same label. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.clickDialogConfirm(text: String) = onAllNodesWithText(text).onLast().performClick()
