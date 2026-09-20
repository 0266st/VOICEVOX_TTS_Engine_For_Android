
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
// VOICEVOX CORE・VOICEVOX ONNX Runtime・音声モデル（VVM）を取得する。
// バイナリはgitで管理せず、このタスクでダウンロードして所定の場所に置く。
fun download(url: String, dest: File) {
    if (dest.exists()) return
    dest.parentFile.mkdirs()
    println("Downloading $url ...")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    val tmp = File(dest.path + ".part")
    val res = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
    check(res.statusCode() == 200) { "Failed to download $url: HTTP ${res.statusCode()}" }
    tmp.renameTo(dest)
}

tasks.register("downloadVoicevox") {
    val coreVersion = libs.versions.voicevox.get()
    val ortVersion = libs.versions.voicevoxOnnxruntime.get()
    val vvmVersion = libs.versions.voicevoxVvm.get()
    val downloadDir = layout.buildDirectory.dir("voicevox-downloads").get().asFile
    // mavenLocal() が実際に見る場所（maven.repo.local や settings.xml の設定に従う）。~/.m2/repository に固定しない
    val mavenLocalDir = File(repositories.withType<MavenArtifactRepository>().getByName("MavenLocal").url)
    val jniLibsDir = file("src/main/jniLibs")
    val rawDir = file("src/main/res/raw")

    doLast {
        val corePackages = File(downloadDir, "java_packages-$coreVersion.zip")
        download("https://github.com/VOICEVOX/voicevox_core/releases/download/$coreVersion/java_packages.zip", corePackages)
        copy { from(zipTree(corePackages)); into(mavenLocalDir) }

        // Onnxruntime.loadOnce() は既定で libvoicevox_onnxruntime.so を読み込む
        for ((ortAbi, androidAbi) in listOf("arm64" to "arm64-v8a", "x64" to "x86_64")) {
            val name = "voicevox_onnxruntime-android-$ortAbi-$ortVersion"
            val tgz = File(downloadDir, "$name.tgz")
            download("https://github.com/VOICEVOX/onnxruntime-builder/releases/download/voicevox_onnxruntime-$ortVersion/$name.tgz", tgz)
            copy {
                from(tarTree(tgz)) { include("$name/lib/libvoicevox_onnxruntime.so") }
                eachFile { path = sourceName }
                includeEmptyDirs = false
                into(File(jniLibsDir, androidAbi))
            }
        }

        // 1.vvm に冥鳴ひまり（スタイルID 14）が入っている
        val vvm = File(downloadDir, "vvm-$vvmVersion/1.vvm")
        download("https://github.com/VOICEVOX/voicevox_vvm/releases/download/$vvmVersion/1.vvm", vvm)
        copy { from(vvm); into(rawDir); rename { "model.vvm" } }
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

    // voicevox runtime
    implementation(libs.voicevox.core)

    // gson
    implementation(libs.gson)
}