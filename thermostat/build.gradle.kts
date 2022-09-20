plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

android {
    compileSdk = 33

    defaultConfig {
        applicationId = "com.nabto.edge.thermostatdemo"
        minSdk = 26
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Android dependencies
    implementation ("androidx.core:core-ktx:1.8.0")
    implementation ("androidx.appcompat:appcompat:1.5.0")
    implementation ("com.google.android.material:material:1.6.1")
    implementation ("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation ("androidx.navigation:navigation-fragment-ktx:2.5.1")
    implementation ("androidx.navigation:navigation-ui-ktx:2.5.1")
    implementation ("androidx.navigation:navigation-dynamic-features-fragment:2.5.1")
    implementation ("androidx.legacy:legacy-support-v4:1.0.0")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation ("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
    implementation ("com.google.android.material:material:1.6.1")
    implementation ("com.google.android.gms:play-services-vision:20.1.3")

    // Kotlin dependencies
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.3.2")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.annotation:annotation:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation(project(mapOf("path" to ":sharedcode")))

    // Koin dependency injection
    val koinVersion = "3.2.0"
    implementation ("io.insert-koin:koin-core:$koinVersion")
    implementation ("io.insert-koin:koin-android:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-workmanager:$koinVersion")
    implementation ("io.insert-koin:koin-androidx-navigation:$koinVersion")
    testImplementation ("io.insert-koin:koin-test:$koinVersion")

    // Nabto dependencies
    implementation ("com.nabto.edge.client:library:2.2.0")
    implementation ("com.nabto.edge.client:library-ktx:2.2.0")
    implementation ("com.nabto.edge.client:iam-util:2.2.0")
    implementation ("com.nabto.edge.client:iam-util-ktx:2.2.0")

    // Room persistence library to use a database abstracted over sqlite
    val roomVersion = "2.4.2"
    implementation ("androidx.room:room-runtime:$roomVersion")
    kapt ("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // Test dependencies
    testImplementation ("junit:junit:4.13.2")
    androidTestImplementation ("androidx.test.ext:junit:1.1.3")
    androidTestImplementation ("androidx.test.espresso:espresso-core:3.4.0")
}