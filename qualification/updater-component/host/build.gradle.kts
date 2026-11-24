plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android {
 namespace = "com.frpboy.seali"
 compileSdk = 36
 defaultConfig { applicationId = "com.frpboy.seali.updaterqualification"; minSdk = 26; targetSdk = 36; versionCode = 10; versionName = "1.0.0" }
 buildFeatures { buildConfig = true }
 buildTypes { debug { buildConfigField("boolean", "QUALIFY_GITHUB_UPDATER", "true"); buildConfigField("String", "FLAVOR", "\"generic\"") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.10")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
}
