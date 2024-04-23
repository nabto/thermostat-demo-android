plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

// We keep this here so it can be exposed to BuildConfig.
val nabtoWrapperVersion = "2.4.0"

android {
    compileSdk = 34
    namespace = "com.nabto.edge.sharedcode"

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "NABTO_WRAPPER_VERSION", "\"${nabtoWrapperVersion}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    kapt {
        arguments {
            arg("room.schemaLocation", "$projectDir/schemas")
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
}

dependencies {
    // Android dependencies
    api (libs.androidx.core.ktx)
    api (libs.androidx.appcompat)
    api (libs.material)
    api (libs.androidx.constraintlayout)
    api (libs.androidx.navigation.fragment.ktx)
    api (libs.androidx.navigation.ui.ktx)
    api (libs.androidx.navigation.dynamic.features.fragment)
    api (libs.androidx.legacy.support.v4)
    api (libs.androidx.lifecycle.runtime.ktx)
    api (libs.androidx.lifecycle.livedata.ktx)
    api (libs.play.services.vision)
    api (libs.androidx.preference.ktx)

    // Nabto dependencies
    api ("com.nabto.edge.client:library:$nabtoWrapperVersion")
    api ("com.nabto.edge.client:library-ktx:$nabtoWrapperVersion")
    api ("com.nabto.edge.client:iam-util:$nabtoWrapperVersion")
    api ("com.nabto.edge.client:iam-util-ktx:$nabtoWrapperVersion")

    // Kotlin dependencies
    api (libs.kotlinx.serialization.json)
    api (libs.kotlinx.serialization.cbor)
    api (libs.kotlinx.coroutines.core)
    api (libs.kotlinx.coroutines.android)
    api (libs.androidx.annotation)
    api (libs.androidx.lifecycle.viewmodel.ktx)
    api (libs.androidx.lifecycle.process)

    // Room persistence library to use a database abstracted over sqlite
    api (libs.androidx.room.runtime)
    kapt (libs.androidx.room.compiler)
    api (libs.androidx.room.ktx)

    // Koin dependency injection
    api (libs.koin.core)
    api (libs.koin.android)
    api (libs.koin.androidx.workmanager)
    api (libs.koin.androidx.navigation)
    testImplementation (libs.koin.test)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}