package com.linux.recipes.ui

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.linux.recipes.UiTest
import com.linux.recipes.data.Backup
import com.linux.recipes.data.FakeServer
import com.linux.recipes.data.PdfDownload
import com.linux.recipes.ui.backup.BackupContent
import com.linux.recipes.ui.backup.BackupState
import com.linux.recipes.ui.share.ShareContent
import com.linux.recipes.ui.share.SharePreviewScreen
import com.linux.recipes.ui.share.SharingServices
import com.linux.recipes.ui.theme.RecipesTheme
import kotlinx.coroutines.MainScope
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Accessibility and screenshot baselines (light, dark, 200 % font) of the share sheet, the link preview and Backup. */
@OptIn(ExperimentalTestApi::class)
class ShareScreensTest : UiTest() {
    private val server = FakeServer()
    private val shares = FakeShareRepository().apply { links.value = listOf(testLink) }
    private val sharing = SharingServices(shares, PdfDownload(server.api()), backup = null, consent = null)
    private val backup = Backup(server.api(), FakeRecipeRepository())

    @Composable
    private fun OnSheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                Box(Modifier.padding(top = 28.dp)) { content() }
            }
        }
    }

    @Composable
    private fun ShareSheetScreen() = OnSheet { ShareContent(sharing, FakeFileActions().actions, potatoSalad, onClose = {}) }

    @Composable
    private fun PreviewScreen() = SharePreviewScreen(shares, SHARE_TOKEN, onBack = {}, onAccepted = {})

    @Composable
    private fun BackupScreen() {
        val scope = remember { MainScope() }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(20.dp)) { BackupContent(remember { BackupState(backup, FakeFileActions().actions, scope) }, backup) }
        }
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = runComposeUiTest {
        setContent { RecipesTheme(darkTheme = dark) { testDoubles(content) } }
        waitForIdle()
        onRoot().captureRoboImage("src/androidHostTest/screenshots/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun both(name: String, content: @Composable () -> Unit) {
        shot(name, dark = false, content)
        shot(name, dark = true, content)
    }

    @Test
    fun shareSheet() = both("share_sheet") { ShareSheetScreen() }

    @Test
    fun sharePreview() = both("share_preview") { PreviewScreen() }

    @Test
    fun backupSection() = both("backup_section") { BackupScreen() }

    /** 200 % font scale, the largest Android offers. */
    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("large_font_share_sheet", dark = false) { ShareSheetScreen() }
        shot("large_font_share_preview", dark = false) { PreviewScreen() }
        shot("large_font_backup_section", dark = false) { BackupScreen() }
    }

    // ---------- accessibility: what TalkBack sees ----------

    private fun everyClickableIsLabelled(content: @Composable () -> Unit) = runComposeUiTest {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        lateinit var view: View
        setContent {
            view = LocalView.current
            RecipesTheme(darkTheme = false) { testDoubles(content) }
        }
        waitForIdle()
        val provider = assertNotNull(view.accessibilityNodeProvider)
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            nodes += n
            n.children.forEach(::walk)
        }
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        // Nodes scrolled out of view get no platform node until they are scrolled in; their semantics text stands in.
        fun semanticsLabel(node: SemanticsNode) = listOfNotNull(
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
        ).joinToString(" ").trim()
        fun label(node: SemanticsNode): String {
            val own = provider.createAccessibilityNodeInfo(if (node.isRoot) View.NO_ID else node.id)
                ?.let { listOfNotNull(it.text, it.contentDescription).joinToString(" ").trim() } ?: semanticsLabel(node)
            return own.ifEmpty { node.children.map(::label).filter { it.isNotEmpty() }.joinToString(" ") }
        }
        val clickable = nodes.filter { SemanticsActions.OnClick in it.config }
        assertTrue(clickable.isNotEmpty(), "screen has clickable nodes")
        val unlabelled = clickable.filter { label(it).isEmpty() }
        assertTrue(unlabelled.isEmpty(), "clickable nodes without text or description: ${unlabelled.map { it.id }}")
    }

    @Test
    fun shareSheetClickablesAreLabelled() = everyClickableIsLabelled { ShareSheetScreen() }

    @Test
    fun previewClickablesAreLabelled() = everyClickableIsLabelled { PreviewScreen() }

    @Test
    fun backupClickablesAreLabelled() = everyClickableIsLabelled { BackupScreen() }

    @Test
    fun detailShareAndPdfActionsAreLabelled() = everyClickableIsLabelled {
        CompositionLocalProvider(com.linux.recipes.ui.share.LocalSharing provides sharing) {
            com.linux.recipes.ui.detail.RecipeDetailScreen(
                remember { com.linux.recipes.ui.detail.RecipeDetailViewModel(FakeRecipeRepository(listOf(potatoSalad)), potatoSalad.id) }, {}, {},
            )
        }
    }
}
