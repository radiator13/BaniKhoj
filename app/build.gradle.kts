plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.banikhoj"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.banikhoj"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)
}

// ---- Native Rust core (libgurbanidb.so) ----

val rustDir = rootProject.file("rust")
val rustJniLibs = File(projectDir, "build/rustJniLibs")
val rustTargetDir = File(projectDir, "build/rustTarget")

val buildRust by tasks.registering(Exec::class) {
    workingDir(rustDir)
    executable("cargo")
    args(
        "ndk", "--platform", "26", "-t", "arm64-v8a",
        "-o", rustJniLibs.absolutePath,
        "build", "--release", "--locked",
    )
    environment("CARGO_TARGET_DIR", rustTargetDir.absolutePath)
    inputs.files(
        fileTree(File(rustDir, "src")) { include("**/*.rs") },
        File(rustDir, "Cargo.toml"),
        File(rustDir, "Cargo.lock"),
    )
    outputs.dir(rustJniLibs)
}

android {
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRust)
}
