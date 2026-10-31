plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.dailyflow.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dailyflow.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0-task-organize"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("$projectDir/schemas")
    if (providers.gradleProperty("hostRenderProbe").isPresent) sourceSets.getByName("test").java.srcDir("src/hostRenderProbe/java")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    maxParallelForks = 1
    forkEvery = 1L // Isolate Robolectric/Compose global loopers between test classes.
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = providers.gradleProperty("verboseUiTests").isPresent }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("robolectricDependencyDir").orNull?.let {
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", it)
    }
}
dependencies {
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Keep processor transitive libraries aligned with the pinned toolchain.
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21", "com.google.guava:guava:32.0.1-jre", "com.google.devtools.ksp:symbol-processing-api:2.0.21-1.0.28", "androidx.tracing:tracing:1.2.0")
}

