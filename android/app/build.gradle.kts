plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Applying Hilt via alias, relying on legacyVariantApi=true in properties
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.secrets)
}

android {
    namespace = "com.example.mychat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mychat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Fixed dot-notation accessor
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.coil.compose)
    implementation(libs.androidx.health.connect)
    implementation(libs.google.fhir.engine)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.tooling)
}

android {
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/ASL-2.0.txt"
            excludes += "META-INF/LGPL-3.0.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/sun-jaxb.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}
