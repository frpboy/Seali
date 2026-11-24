plugins { id("com.android.application") }
android {
 namespace = "com.frpboy.seali.updaterfixture"
 compileSdk = 36
 defaultConfig {
 applicationId = providers.gradleProperty("fixturePackage").getOrElse("com.frpboy.seali.updaterqualification")
 minSdk = 26; targetSdk = 36
 versionCode = providers.gradleProperty("fixtureVersion").getOrElse("11").toInt()
 versionName = "qualification-fixture"
 }
}
