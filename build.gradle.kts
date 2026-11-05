plugins {
    alias(libs.plugins.android.library) // أو application حسب نوع الموديول لديك
    alias(libs.plugins.kotlin.android)
    kotlin("plugin.serialization") // إضافة تفعيل الـ Serialization هنا
}

android {
    namespace = "com.tungsten.fcl" // أو مساحة الأسماء الخاصة بمشروعك
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // مكتبات الـ Serialization والـ DataStore اللازمة لعمل ThemePreference.kt
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.0")
    
    // باقي الاعتماديات الخاصة بالمشروع...
}
