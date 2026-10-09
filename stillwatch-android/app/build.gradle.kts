import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val connection = Properties().apply {
    rootProject.file("aws-config.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun config(name: String) = "\"" + connection.getProperty(name, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "kr.stillwatch.app"
    compileSdk = 35
    defaultConfig {
        applicationId = if(providers.gradleProperty("workerPilot").orNull=="true")"kr.stillwatch.workerpilot" else "kr.stillwatch.app"
        manifestPlaceholders["appLabel"] = if(providers.gradleProperty("workerPilot").orNull=="true")"StillWatch 작업자" else "StillWatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "API_URL", config("apiUrl"))
        buildConfigField("String", "COGNITO_DOMAIN", config("cognitoDomain"))
        buildConfigField("String", "COGNITO_CLIENT_ID", config("cognitoClientId"))
        buildConfigField("String", "FIREBASE_APP_ID", config("firebaseApplicationId"))
        buildConfigField("String", "FIREBASE_API_KEY", config("firebaseApiKey"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", config("firebaseProjectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", config("firebaseSenderId"))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.google.firebase:firebase-messaging:24.1.1")
    implementation("com.github.espressif:esp-idf-provisioning-android:lib-2.4.4")
    implementation("org.greenrobot:eventbus:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
