plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.macdroid.app"
    compileSdk = 36
    defaultConfig { applicationId = "dev.macdroid.app"; minSdk = 28; targetSdk = 36; versionCode = 2; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
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
