package com.frpboy.seali.util

/** Pure state only. UI/ViewModel wiring must own work and observe each effect once, not re-collect downloads. */
internal class UpdateSessionState {
    enum class Origin { AUTOMATIC, MANUAL }
    enum class Phase { DISCOVERING, AVAILABLE, DOWNLOADING, VERIFYING, READY, PERMISSION, HANDOFF, HANDED_OFF }
    enum class Effect { OPEN_PERMISSION, LAUNCH_INSTALLER }
    sealed class Claim {
        data class Started(val id: String) : Claim()
        data class Focus(val id: String) : Claim()
        object QuietBusy : Claim()
    }
    data class Snapshot(val id: String, val origin: Origin, val phase: Phase, val effect: Effect? = null)
    private var active: Snapshot? = null
    @Synchronized fun snapshot(): Snapshot? = active
    @Synchronized fun claim(origin: Origin): Claim {
        active?.let { return if (origin == Origin.MANUAL) Claim.Focus(it.id) else Claim.QuietBusy }
        val id = java.util.UUID.randomUUID().toString()
        active = Snapshot(id, origin, Phase.DISCOVERING)
        return Claim.Started(id)
    }
    @Synchronized fun discovered(id: String): Boolean = advance(id, Phase.DISCOVERING, Phase.AVAILABLE)
    @Synchronized fun startDownload(id: String): Boolean = advance(id, Phase.AVAILABLE, Phase.DOWNLOADING)
    @Synchronized fun verifying(id: String): Boolean = advance(id, Phase.DOWNLOADING, Phase.VERIFYING)
    @Synchronized fun ready(id: String): Boolean = advance(id, Phase.VERIFYING, Phase.READY)
    @Synchronized fun requestHandoff(id: String, hasPermission: Boolean): Boolean {
        val value = active ?: return false
        if (value.id != id || value.phase != Phase.READY) return false
        active = value.copy(phase = if (hasPermission) Phase.HANDOFF else Phase.PERMISSION,
            effect = if (hasPermission) Effect.LAUNCH_INSTALLER else Effect.OPEN_PERMISSION)
        return true
    }
    @Synchronized fun permissionResult(id: String, granted: Boolean): Boolean {
        val value = active ?: return false
        // A callback cannot arrive legitimately before its OPEN_PERMISSION effect was consumed.
        if (value.id != id || value.phase != Phase.PERMISSION || value.effect != null) return false
        if (!granted) { active = null; return false }
        active = value.copy(phase = Phase.HANDOFF, effect = Effect.LAUNCH_INSTALLER)
        return true
    }
    /** Consume BEFORE the main-thread launcher side effect. Recreation must never re-send the launch. */
    @Synchronized fun consumeEffect(id: String): Effect? {
        val value = active ?: return null
        if (value.id != id) return null
        val effect = value.effect ?: return null
        active = value.copy(effect = null,
            phase = if (effect == Effect.LAUNCH_INSTALLER) Phase.HANDED_OFF else value.phase)
        return effect
    }
    @Synchronized fun isCurrent(id: String): Boolean = active?.id == id
    /** Returns whether a failure should be visible. Calling code cancels owned work and file reservation. */
    @Synchronized fun fail(id: String): Boolean {
        val value = active ?: return false
        if (value.id != id) return false
        active = null
        return value.origin == Origin.MANUAL
    }
    @Synchronized fun dismiss(id: String): Boolean {
        if (active?.id != id) return false
        active = null
        return true
    }
    private fun advance(id: String, from: Phase, to: Phase): Boolean {
        val value = active ?: return false
        if (value.id != id || value.phase != from) return false
        active = value.copy(phase = to)
        return true
    }
}
