package com.frpboy.seali

import com.frpboy.seali.util.UpdateSessionState
import com.frpboy.seali.util.UpdateSessionState.*
import org.junit.Assert.*
import org.junit.Test

class UpdateSessionStateTest {
    private fun start(s: UpdateSessionState, origin: Origin = Origin.MANUAL) = (s.claim(origin) as Claim.Started).id
    private fun ready(s: UpdateSessionState, id: String) {
        assertTrue(s.discovered(id)); assertTrue(s.startDownload(id)); assertTrue(s.verifying(id)); assertTrue(s.ready(id))
    }
    @Test fun manualFocusesAndAutomaticYieldsDuringExistingSession() {
        val s = UpdateSessionState(); val id = start(s, Origin.AUTOMATIC)
        assertEquals(Claim.Focus(id), s.claim(Origin.MANUAL)); assertEquals(Claim.QuietBusy, s.claim(Origin.AUTOMATIC))
    }
    @Test fun installerEffectIsConsumedOnceAndFinishedCannotReenter() {
        val s = UpdateSessionState(); val id = start(s); ready(s,id)
        assertTrue(s.requestHandoff(id,true)); assertEquals(Effect.LAUNCH_INSTALLER,s.consumeEffect(id))
        assertNull(s.consumeEffect(id)); assertFalse(s.requestHandoff(id,true)); assertFalse(s.ready(id))
    }
    @Test fun permissionResultMustFollowConsumedMatchingEffect() {
        val s = UpdateSessionState(); val id = start(s); ready(s,id); s.requestHandoff(id,false)
        assertFalse(s.permissionResult(id,true)); assertEquals(Effect.OPEN_PERMISSION,s.consumeEffect(id))
        assertTrue(s.permissionResult(id,true)); assertFalse(s.permissionResult(id,true))
        assertEquals(Effect.LAUNCH_INSTALLER,s.consumeEffect(id)); assertNull(s.consumeEffect(id))
    }
    @Test fun dismissalAndRetryRejectStalePermissionAndDownloadCallbacks() {
        val s = UpdateSessionState(); val old = start(s); ready(s,old); s.requestHandoff(old,false); s.consumeEffect(old)
        assertTrue(s.dismiss(old)); val new = start(s)
        assertNotEquals(old,new); assertFalse(s.permissionResult(old,true)); assertFalse(s.ready(old)); assertNull(s.consumeEffect(old))
        assertTrue(s.isCurrent(new)); assertFalse(s.fail(old)); assertTrue(s.isCurrent(new))
    }
    @Test fun automaticFailuresStayQuietManualFailuresAreVisible() {
        val s = UpdateSessionState(); assertFalse(s.fail(start(s,Origin.AUTOMATIC))); assertNull(s.snapshot())
        assertTrue(s.fail(start(s))); assertNull(s.snapshot())
    }
    @Test fun deniedPermissionRequiresNewSessionIdentity() {
        val s = UpdateSessionState(); val old = start(s); ready(s,old); s.requestHandoff(old,false); s.consumeEffect(old)
        assertFalse(s.permissionResult(old,false)); assertNull(s.snapshot()); assertNotEquals(old,start(s))
    }
    @Test fun newProcessRestoresNoInstallEffect() {
        val s = UpdateSessionState(); val id = start(s); ready(s,id); s.requestHandoff(id,true)
        val restarted = UpdateSessionState(); assertNull(restarted.snapshot()); assertNull(restarted.consumeEffect(id)); assertNotEquals(id,start(restarted))
    }
}
