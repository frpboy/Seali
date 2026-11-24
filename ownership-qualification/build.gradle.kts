plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
val exactOwnershipSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include("com/frpboy/seali/ui/page/UpdateCoordinator.kt")
        include("com/frpboy/seali/ui/page/UpdateCoordinatorHost.kt")
        include("com/frpboy/seali/ui/page/UpdateEnvironment.kt")
        include("com/frpboy/seali/ui/page/UpdateDialog.kt")
        include("com/frpboy/seali/ui/page/UpdateProgressPage.kt")
        include("com/frpboy/seali/util/UpdateSessionState.kt")
        include("com/frpboy/seali/util/UpdateFileStore.kt")
        include("com/frpboy/seali/util/UpdateBlockingCall.kt")
        include("com/frpboy/seali/util/UpdateDownloadStream.kt")
        include("com/frpboy/seali/util/ApkUpdateVerifier.kt")
        include("com/frpboy/seali/util/ApkArchiveReader.kt")
    }
    into(layout.buildDirectory.dir("generated/exact/main"))
}
android {
    namespace = "com.frpboy.seali.ownershipfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.frpboy.seali.ownershipfixture"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "ownership-fixture-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/exact/main"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
kotlin { jvmToolchain(21) }
tasks.named("preBuild") { dependsOn(exactOwnershipSources) }
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation(libs.okhttp)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
