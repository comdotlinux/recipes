package com.linux.recipes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.linux.recipes.App

class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) {
        opened += uri
    }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setApp(
    session: FakeSessionRepository,
    recipes: FakeRecipeRepository,
    uriHandler: UriHandler = RecordingUriHandler(),
    darkTheme: Boolean = false,
) {
    setContent {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            App(session, recipes, darkTheme)
        }
    }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.waitForText(text: String, substring: Boolean = false) =
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.waitForNoText(text: String) =
    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

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
