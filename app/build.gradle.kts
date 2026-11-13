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

        // true = la app usa el servidor (carpeta /server) y SQL Server; false = demo en memoria sin servidor.
        // Se puede cambiar sin tocar este archivo: en gradle.properties, SIRA_USAR_SERVIDOR=false
        val usarServidor = (project.findProperty("SIRA_USAR_SERVIDOR") as String?)?.toBoolean() ?: true
        buildConfigField("boolean", "USAR_SERVIDOR", usarServidor.toString())
    }

    buildTypes {
        debug {
            // 10.0.2.2 = tu computadora vista desde el emulador de Android.
            // En un teléfono físico usa la IP de tu PC en la red (p. ej. http://192.168.1.50:8080/api/).
            val apiUrl = (project.findProperty("SIRA_API_URL") as String?) ?: "http://10.0.2.2:8080/api/"
            buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
            buildConfigField("String", "DEMO_LOGIN_EMAIL", "\"demo@sira.app\"")
            // Debe coincidir con SEED_DEMO_PASSWORD del servidor (npm run seed:dev).
            buildConfigField("String", "DEMO_LOGIN_PASSWORD", "\"SiraDemo2026!\"")
            buildConfigField("String", "DEMO_ADMIN_EMAIL", "\"admin@sira.app\"")
            buildConfigField("String", "DEMO_NURSE_EMAIL", "\"enfermeria@sira.app\"")
            buildConfigField("String", "DEMO_TUTOR_EMAIL", "\"tutor@sira.app\"")
        }
        release {
            // En producción, solo HTTPS.
            val apiUrlRelease = (project.findProperty("SIRA_API_URL_RELEASE") as String?) ?: "https://CAMBIAR.dominio.edu.mx/api/"
            buildConfigField("String", "API_BASE_URL", "\"$apiUrlRelease\"")
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
