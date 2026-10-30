import java.util.Properties

plugins { id("com.android.application") }

val localSettings = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val defaultTunnelId = localSettings.getProperty("phoneLink.defaultTunnelId", "")
require(defaultTunnelId.isEmpty() || defaultTunnelId.matches(Regex("tunnel_(?:[a-z0-9]{4}_)?[a-z0-9]{32}")))

android {
    namespace = "dev.androidmcp"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.androidmcp"
        minSdk = 30
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_TUNNEL_ID", "\"$defaultTunnelId\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
