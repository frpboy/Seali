plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Deliberately a separate Gradle root: cannot include Seali App/Application, native libraries, secrets,
// runtime installers or release tasks. The EXACT presentation source and test are copied, not rewritten.
val presentationSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") { include("com/frpboy/seali/ui/page/UpdateProgressPage.kt")
        include("com/frpboy/seali/util/ApkUpdateVerifier.kt")
        include("com/frpboy/seali/util/ApkArchiveReader.kt") }
    into(layout.buildDirectory.dir("generated/presentation/main"))
}
val presentationTestSources by tasks.registering(Sync::class) {
    from("../app/src/androidTest/java") { include("com/frpboy/seali/UpdateProgressPageTest.kt")
        include("com/frpboy/seali/ApkArchiveReaderFixtureTest.kt") }
    into(layout.buildDirectory.dir("generated/presentation/androidTest"))
}
android {
    namespace = "com.frpboy.seali.updaterfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.frpboy.seali.updaterfixture"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "fixture-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets {
        getByName("main").java.srcDir(layout.buildDirectory.dir("generated/presentation/main"))
        getByName("androidTest").java.srcDir(layout.buildDirectory.dir("generated/presentation/androidTest"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(presentationSources, presentationTestSources) }
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
