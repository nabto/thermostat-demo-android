import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlinx-serialization")
}

android {
    compileSdk = 34
    namespace = "com.nabto.edge.tunnelvideodemo"
    ndkVersion = "20.1.5948944"

    defaultConfig {
        applicationId = "com.nabto.edge.tunnelvideodemo"
        minSdk = 26
        versionCode = 20
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            ndkBuild {
                val propertiesKey = "gstAndroidRoot"
                val envKey = "GSTREAMER_ROOT_ANDROID"

                val localProperties = project.rootProject.file("local.properties")
                val properties = 
                    if (localProperties.exists()) {
                        val props = Properties()
                        props.load(localProperties.inputStream())
                        props
                    } else {
                        null
                    }

                val gstRoot: String? =
                    if (project.properties.containsKey(propertiesKey)) {
                        project.properties[propertiesKey] as String
                    } else if (properties != null && properties!!.containsKey(propertiesKey)) {
                        properties!![propertiesKey] as String
                    } else {
                        System.getenv()[envKey]
                    }

                gstRoot?.let {
                    val main = "src/main"
                    arguments("NDK_APPLICATION_MK=$main/jni/Application.mk", "GSTREAMER_JAVA_SRC_DIR=$main/java", "GSTREAMER_ROOT_ANDROID=$gstRoot", "GSTREAMER_ASSETS_DIR=$main/assets")
                    targets("tunnel-video")
                    abiFilters("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                } ?: run {
                    throw GradleException("$envKey must be set, or define $propertiesKey in your local.properties or gradle.properties")
                }
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
    externalNativeBuild {
        ndkBuild {
            path ("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    // Dependencies are all pulled from sharedcode module
    implementation (project(mapOf("path" to ":sharedcode")))
    implementation(libs.exoplayer.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}