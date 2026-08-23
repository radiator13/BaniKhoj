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
        versionCode = 1
        versionName = "0.1.0"

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

val rustJniLibs = layout.buildDirectory.dir("rustJniLibs")
val rustTargetDir = layout.buildDirectory.dir("rustTarget")

val buildRust by tasks.registering(Exec::class) {
    workingDir(rootProject.file("rust"))
    executable("cargo")
    args(
        "ndk", "--platform", "26", "-t", "arm64-v8a",
        "-o", rustJniLibs.get().asFile.absolutePath,
        "build", "--release", "--locked",
    )
    environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
    inputs.files(fileTree(rootProject.file("rust/src")) { include("**/*.rs") }, rootProject.file("rust/Cargo.toml"), rootProject.file("rust/Cargo.lock"))
    outputs.dir(rustJniLibs)
}

android {
    sourceSets["main"].jniLibs.srcDir(rustJniLibs)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRust)
}
