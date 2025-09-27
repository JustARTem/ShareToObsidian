import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.*

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.justartem.tools.sharetoobsidian"
    compileSdk = 35

    // Получаем данные из Git
    val versionCodeFromGit = getCommitCount()
    val versionNameFromGit = "1.0.${getCommitCount()}" // или использовать гит-хэш

    defaultConfig {
        applicationId = "ru.justartem.tools.sharetoobsidian"
        minSdk = 28
        targetSdk = 35
        versionCode = versionCodeFromGit
        versionName = versionNameFromGit

//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
//            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }


}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity:1.7.2")
    implementation("org.jsoup:jsoup:1.16.1") // Для парсинга HTML
    implementation("com.google.android.material:material:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:truth:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

}

// Функция: количество коммитов в ветке
fun getCommitCount(): Int {
    val command = "git rev-list --count HEAD"
    return try {
        command.execute().trim().toInt()
    } catch (e: Exception) {
        1 // fallback
    }
}

// Вспомогательный метод для выполнения команды
fun String.execute(): String {
    val parts = this.split("\\s".toRegex())
    val process = Runtime.getRuntime().exec(parts.toTypedArray())
    process.waitFor()
    return process.inputStream.bufferedReader().readText()
}
