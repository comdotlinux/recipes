package com.linux.recipes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.linux.recipes.UiTest
import com.linux.recipes.data.Backup
import com.linux.recipes.data.DefaultRecipeRepository
import com.linux.recipes.data.FakeAccount
import com.linux.recipes.data.ID_A
import com.linux.recipes.data.ID_B
import com.linux.recipes.data.IMG_1
import com.linux.recipes.data.PdfDownload
import com.linux.recipes.data.RecipeCache
import com.linux.recipes.data.recipe
import com.linux.recipes.data.tempDir
import com.linux.recipes.ui.settings.SettingsScreen
import com.linux.recipes.ui.settings.SettingsViewModel
import com.linux.recipes.ui.share.LocalFileActions
import com.linux.recipes.ui.share.LocalSharing
import com.linux.recipes.ui.share.SharingServices
import com.linux.recipes.ui.theme.RecipesTheme
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.readByteArray

/** "Backup" in Settings: export, restore, the storage warning, progress with cancel, and every summary (AC27). */
@OptIn(ExperimentalTestApi::class)
class BackupUiTest : UiTest() {
    private val files = FakeFileActions()
    private val jpeg = byteArrayOf(-1, -40, -1) + ByteArray(2000) { 3 }

    private fun account() = FakeAccount().apply {
        recipes[ID_A] = recipe(ID_A, 1, title = "Kartoffelsalat", imageIds = listOf(IMG_1))
        recipes[ID_B] = recipe(ID_B, 2, title = "Strudel")
        rev = 2
        images[IMG_1] = Triple(ID_A, "image/jpeg", jpeg)
    }

    private fun backupOf(account: FakeAccount) =
        Backup(account.api(), DefaultRecipeRepository(account.api(), RecipeCache(tempDir()), currentToken = { "t" }) {}, currentToken = { "t" })

    private fun ComposeUiTest.settings(account: FakeAccount) {
        val sharing = SharingServices(FakeShareRepository(), PdfDownload(account.api()), backupOf(account), consent = null)
        setScreen {
            CompositionLocalProvider(LocalSharing provides sharing, LocalFileActions provides files.actions) {
                RecipesTheme(darkTheme = false) {
                    SettingsScreen(remember { SettingsViewModel(FakeSessionRepository(signedIn = true)) }, onBack = {})
                }
            }
        }
        reveal("Export")
    }

    private fun ComposeUiTest.reveal(text: String) = onNode(hasText(text)).performScrollTo()

    private fun ComposeUiTest.press(text: String) {
        reveal(text)
        onNode(hasText(text)).performClick()
    }

    private suspend fun exported(): ByteArray {
        val buffer = kotlinx.io.Buffer()
        backupOf(account()).export { write -> write(buffer); true }
        return buffer.readByteArray()
    }

    @Test
    fun exportSavesTheFileAndSummarises() = runComposeUiTest {
        settings(account())
        press("Export")
        waitForText("Backup saved with 2 recipes and 1 pictures.")
        assertEquals("recipes-backup.jsonl", files.saved.single().first)
        assertTrue(files.saved.single().second.decodeToString().startsWith("{\"format\":\"recipes-backup\""))
    }

    @Test
    fun exportStopsWithTheRecipesWhosePicturesAreMissing() = runComposeUiTest {
        val source = account()
        source.images.remove(IMG_1)
        settings(source)
        press("Export")
        waitForText("Some pictures are not on this device yet", substring = true)
        reveal("Kartoffelsalat")
        assertTrue(files.saved.isEmpty())
    }

    @Test
    fun exportCancelledInTheSaveDialogShowsNothing() = runComposeUiTest {
        files.cancelSave = true
        settings(account())
        press("Export")
        waitForText("Restore")
        assertTrue(files.saved.isEmpty())
    }

    @Test
    fun restoreIntoAnEmptyAccount() = runComposeUiTest {
        files.pickedLater = { exported() }
        val target = FakeAccount()
        settings(target)
        press("Restore")
        waitForText("Restored 2 recipes and 1 pictures. Already up to date: 0.")
        assertEquals(setOf(ID_A, ID_B), target.recipes.keys)
        assertEquals(1, files.releases)
    }

    @Test
    fun restoreOfAFileThatIsNoBackupIsRefused() = runComposeUiTest {
        files.picked = "hello".encodeToByteArray()
        settings(FakeAccount())
        press("Restore")
        waitForText("This file is not a backup from Recipes, or it is damaged.")
    }

    @Test
    fun overCapWarnsFirstThenListsTheSkippedPictures() = runComposeUiTest {
        files.pickedLater = { exported() }
        val target = FakeAccount(capBytes = 10)
        settings(target)
        press("Restore")
        waitForText("Not all pictures will fit")
        click("Cancel")
        waitForNoText("Not all pictures will fit")
        assertEquals(0, target.writes)
        assertEquals(1, files.releases)
        press("Restore")
        waitForText("Not all pictures will fit")
        click("Restore anyway")
        waitForText("Restored 2 recipes and 0 pictures. Already up to date: 0.")
        reveal("Pictures not restored:")
        reveal("Kartoffelsalat: your picture storage is full")
    }

    @Test
    fun progressShowsAndCancelStopsTheRestore() = runComposeUiTest {
        files.pickedLater = { exported() }
        val target = FakeAccount()
        val gate = CompletableDeferred<Unit>()
        target.server.handler.let { original ->
            target.server.handler = { request ->
                if (request.method == HttpMethod.Put) gate.await()
                original(this, request)
            }
        }
        settings(target)
        press("Restore")
        waitForText("0 of 3 done")
        press("Cancel")
        waitForText("The restore stopped before the end. Run it again to finish it; nothing is restored twice.")
        gate.complete(Unit)
    }

    @Test
    fun offlineRestoreSaysSo() = runComposeUiTest {
        files.pickedLater = { exported() }
        val target = FakeAccount()
        target.server.offline = true
        settings(target)
        press("Restore")
        waitForText("The server cannot be reached right now. Please check your connection and try again.")
    }

    @Test
    fun everySkipCodeHasItsOwnTextAndUnknownCodesAGenericOne() {
        val codes = listOf(
            "recipe_limit", "recipe_storage_cap", "recipe_too_large", "storage_cap", "image_too_large", "image_limit",
            "unsupported_media_type", "recipe_missing", "precondition_failed",
        )
        val texts = codes.map { com.linux.recipes.ui.backup.reasonOf(it) }
        assertEquals(codes.size, texts.toSet().size)
        val generic = com.linux.recipes.ui.backup.reasonOf("something_new")
        assertTrue(generic !in texts)
        assertEquals(generic, com.linux.recipes.ui.backup.reasonOf("invalid_body"))
    }
}
