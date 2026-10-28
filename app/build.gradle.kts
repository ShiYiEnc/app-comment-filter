plugins { id("com.android.application") }

android {
    namespace = "io.github.kuaishou.filter"
    compileSdk = 36
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "io.github.kuaishou.filter"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.0"
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
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
