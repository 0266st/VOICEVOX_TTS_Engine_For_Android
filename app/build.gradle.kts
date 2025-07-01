
import java.io.FileOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    mavenLocal()
}

android {
    namespace = "dev.ztssst.voicevox_tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ztssst.voicevox_tts"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}
fun ZipInputStream.forEach(fn: (ZipEntry)->Unit){
    var entry = nextEntry
    while (entry != null){
        fn(entry)
        entry = nextEntry
    }
}

tasks.register("downloadVoicevox") {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    val voicevoxZip = uri("https://github.com/VOICEVOX/voicevox_core/releases/download/${project.libs.voicevox.core.get().version}/java_packages.zip")
    val req = HttpRequest.newBuilder().GET().uri(voicevoxZip).build()
    val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())

    ZipInputStream(res.body()).use { zis -> zis.forEach {
        println("Extracting ${it.name}...")
        val dest = File(project.repositories.mavenLocal().url.path, it.name)
        if(it.isDirectory) dest.mkdirs() else zis.copyTo(FileOutputStream(dest))
    }
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
    implementation(libs.androidx.media3.common.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // onnx runtime
    implementation(libs.onnxruntime.android)

    // voicevox runtime
    implementation(libs.voicevox.core)

    // gson
    implementation(libs.gson)
}