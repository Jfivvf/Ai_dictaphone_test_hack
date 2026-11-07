import java.net.URL
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-2.0.1.aar")

val downloadSherpaAar by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        val target = sherpaAar.asFile
        if (target.exists() && sha256(target) == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7") {
            return@doLast
        }
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".part")
        URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar")
            .openConnection().apply { connectTimeout = 30000; readTimeout = 180000 }
            .getInputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        val digest = sha256(temporary)
        check(digest == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7") {
            "Sherpa-ONNX AAR checksum mismatch: $digest"
        }
        check(temporary.renameTo(target)) { "Не удалось сохранить Sherpa-ONNX AAR" }
    }
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

tasks.named("preBuild").configure { dependsOn(downloadSherpaAar) }

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.aidictaphone.vktzqm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = false
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation(":sherpa-onnx-2.0.1@aar")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
