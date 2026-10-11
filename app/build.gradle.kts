plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.example.weiterb2"
    compileSdk = 36
    defaultConfig {
        applicationId = providers.gradleProperty("applicationId").getOrElse("com.example.weiterb2")
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "mp3" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").assets.setSrcDirs(buildList {
        add(layout.buildDirectory.dir("generated/catalogAssets"))
        providers.gradleProperty("bundledAssetsDir").orNull?.let { add(file(it)) }
    })
}
val syncCatalogAssets by tasks.registering(Sync::class) {
    from("src/main/assets") { include("catalog.json") }
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}
tasks.named("preBuild") { dependsOn(syncCatalogAssets) }
// Incremental ZIP updates retain freed entry bytes when a bundle is removed.
// A full packaging run creates a fresh APK for each selected asset set.
tasks.matching { it.name in setOf("packageDebug", "packageRelease", "packageDebugAndroidTest") }.configureEach {
    outputs.upToDateWhen { false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    kapt("androidx.room:room-compiler:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
