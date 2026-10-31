plugins { id("com.android.application") }

android {
    namespace = "io.github.appcommentfilter"
    compileSdk = 36
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "io.github.appcommentfilter"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "0.2.4"
        testInstrumentationRunner = "io.github.appcommentfilter.RuntimeStatusInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
