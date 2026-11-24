package com.frpboy.seali.fixture

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.core.content.FileProvider
import com.frpboy.seali.util.UpdateSessionState
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Proposed Android contracts. Not executed. Fake boundary calls must never be described as real install proof. */
class OwnershipFixtureContractTest {
    @get:Rule val compose = createAndroidComposeRule<OwnershipFixtureActivity>()
    private fun waitForDialog() = compose.waitUntil(5000) { compose.activity.owner.ui.value.release != null }
    private fun waitForPermission() = compose.waitUntil(5000) { compose.activity.owner.ui.value.awaitingPermission }

    @Test fun manualAndAutomaticShareOneSessionAndRotationCannotRepeatEffect() {
        compose.runOnUiThread { compose.activity.owner.check(false) }
        waitForDialog()
        val id = compose.activity.owner.ui.value.id
        compose.runOnUiThread { compose.activity.owner.check(true); compose.activity.owner.check(false) }
        compose.runOnIdle { assertEquals(id,compose.activity.owner.ui.value.id); assertEquals(1,compose.activity.environment.discoveries) }
        compose.onNodeWithText("Update").performClick(); waitForPermission()
        val owner = compose.activity.owner
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnIdle { assertSame(owner,compose.activity.owner); assertEquals(id,owner.ui.value.id); assertEquals(0,compose.activity.permissionLaunches) }
        compose.onNodeWithText("Allow app updates in Android").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.environment.permission = true }
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(5000) { compose.activity.installerLaunches == 1 }
        compose.runOnUiThread { owner.permissionContinue(checkNotNull(id)) }
        compose.runOnIdle { assertEquals(1,compose.activity.installerLaunches) }
    }

    @Test fun deniedPermissionIsVisibleAndStaleContinueCannotInstall() {
        compose.runOnUiThread { compose.activity.owner.check(true) }; waitForDialog()
        val id = checkNotNull(compose.activity.owner.ui.value.id)
        compose.onNodeWithText("Update").performClick(); waitForPermission()
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(5000) { compose.activity.owner.ui.value.message?.contains("not granted") == true }
        compose.runOnUiThread { compose.activity.environment.permission = true; compose.activity.owner.permissionContinue(id) }
        compose.runOnIdle { assertEquals(0,compose.activity.installerLaunches) }
    }

    @Test fun maintenanceRequiresConfirmationAndProviderExposesOnlyVerifiedSubtree() {
        compose.runOnUiThread { compose.activity.owner.requestMaintenance() }
        compose.onNodeWithText("Clear retained update files?").assertIsDisplayed()
        compose.onNodeWithText("Keep files").performClick()
        val context = compose.activity
        val file = File(context.filesDir,"update-apks/verified/fixture.apk").also { it.parentFile?.mkdirs(); it.writeText("fixture-only") }
        val temp = File(context.filesDir,"update-apks/temporary/fixture.part").also { it.parentFile?.mkdirs(); it.writeText("fixture-only") }
        try {
            val uri = FileProvider.getUriForFile(context,context.packageName + ".provider",file)
            context.contentResolver.openInputStream(uri).use { assertEquals("fixture-only",it?.bufferedReader()?.readText()) }
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context,context.packageName + ".provider",temp) }
        } finally { file.delete(); temp.delete() }
    }
}
