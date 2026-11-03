plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.macdroid.app"
    compileSdk = 36
    defaultConfig { applicationId = "dev.macdroid.app"; minSdk = 28; targetSdk = 36; versionCode = 5; versionName = "0.1.4"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    val signingPath = providers.environmentVariable("HOPPAIR_ANDROID_SIGNING_FILE").orNull
        ?: providers.environmentVariable("MACDROID_ANDROID_SIGNING_FILE").orNull
    if (signingPath != null) {
        val values = groovy.json.JsonSlurper().parse(file(signingPath)) as Map<*, *>
        val releaseKey = signingConfigs.create("releaseKey") {
            storeFile = file(values["storeFile"] as String)
            storePassword = values["storePassword"] as String
            keyAlias = values["keyAlias"] as String
            keyPassword = values["keyPassword"] as String
        }
        buildTypes.getByName("release").signingConfig = releaseKey
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("junit:junit:4.13.2")
}

val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../../LICENSE"))
    from(rootProject.file("../../NOTICE"))
    from(rootProject.file("../../licenses")) { into("licenses") }
    into(licenseAssets)
}
android.sourceSets.getByName("main").assets.srcDir(licenseAssets)
tasks.named("preBuild") { dependsOn(copyLicenseAssets) }
