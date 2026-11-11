plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.sira"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sira"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEMO_LOGIN_EMAIL", "\"demo@sira.app\"")
            buildConfigField("String", "DEMO_LOGIN_PASSWORD", "\"1234\"")
            buildConfigField("String", "DEMO_ADMIN_EMAIL", "\"admin@sira.app\"")
            buildConfigField("String", "DEMO_NURSE_EMAIL", "\"enfermeria@sira.app\"")
            buildConfigField("String", "DEMO_TUTOR_EMAIL", "\"tutor@sira.app\"")
        }
        release {
            buildConfigField("String", "DEMO_LOGIN_EMAIL", "\"\"")
            buildConfigField("String", "DEMO_LOGIN_PASSWORD", "\"\"")
            buildConfigField("String", "DEMO_ADMIN_EMAIL", "\"\"")
            buildConfigField("String", "DEMO_NURSE_EMAIL", "\"\"")
            buildConfigField("String", "DEMO_TUTOR_EMAIL", "\"\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
