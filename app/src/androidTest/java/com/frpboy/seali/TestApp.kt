package com.frpboy.seali

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Application used by [SealiTestRunner]. Identical to [App] except that the async
 * engine-initialization block can be skipped for one instrumentation run with the
 * `skipAppEngineInit=true` instrumentation argument.
 *
 * The alpha8 EngineGuard binds one engine directory per process and refuses a
 * different one. Tests that install the engine into an isolated fixture directory
 * therefore need a process where the app init never binds the real directory.
 * All other runs use the default behavior, byte-for-byte [App]'s own, so normal
 * app-init coverage is unchanged.
 */
class TestApp : App() {
    override fun initializeEnginesAtStartup() {
        if (InstrumentationRegistry.getArguments().getString("skipAppEngineInit") == "true") {
            return
        }
        super.initializeEnginesAtStartup()
    }
}
