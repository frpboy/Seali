package com.frpboy.seali

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Instantiates [TestApp] instead of [App] in the app process under test. */
class SealiTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestApp::class.java.name, context)
}
