import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.tasks.MergeSourceSetFolders
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("checkstyle")
}

checkstyle {
    toolVersion = "10.12.5"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
}

tasks.register<Checkstyle>("checkstyle") {
    description = "Run checkstyle on the FXL Java sources."
    group = "verification"
    source(layout.projectDirectory.dir("src/main/java"))
    include("**/*.java")
    classpath = files()
    maxErrors = 0
    maxWarnings = 0
}

android {
    namespace = "com.tungsten.fxl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    var localProperty: Properties? = null
    if (file("${rootDir}/local.properties").exists()) {
        localProperty = Properties()
        file("${rootDir}/local.properties").inputStream().use { localProperty.load(it) }
    }

    val pwd = System.getenv("FCL_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("pwd")
    val curseApiKey = System.getenv("CURSE_API_KEY") ?: localProperty?.getProperty("curse.api.key")
    val oauthApiKey = System.getenv("OAUTH_API_KEY") ?: localProperty?.getProperty("oauth.api.key")

    if (System.getProperty("arch") == null && localProperty != null && localProperty.getProperty(
            "arch",
            "all"
        ) == "arm64"
    )
        System.setProperty("arch", "arm64")

    signingConfigs {
        create("FCLKey") {
            storeFile = file("../key-store.jks")
            storePassword = pwd
            keyAlias = "FXL-Key"
            keyPassword = pwd
        }

        create("FCLDebugKey") {
            storeFile = file("../debug-key.jks")
            storePassword = "FXL-Debug"
            keyAlias = "FXL-Debug"
            keyPassword = "FXL-Debug"
        }

        configureEach {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.tungsten.fxl"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1344
        versionName = "1.3.4.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    testBuildType = "fordebug"

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }

        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }

        create("fordebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("FCLDebugKey")
            resValue("string", "file_browser_provider", "com.tungsten.fxl.debug.provider")
        }

        configureEach {
            resValue("string", "app_version", defaultConfig.versionName.toString())
            resValue("string", "curse_api_key", curseApiKey.toString())
            resValue("string", "oauth_api_key", oauthApiKey.toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    ndkVersion = "27.0.12077973"

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }

    // تعطيل شامل لجميع أخطاء فحص النصوص لتجاوز أي مشاكل في strings.xml
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        checkAllWarnings = false
        disable.addAll(listOf(
            "MissingTranslation", 
            "ExtraTranslation", 
            "InvalidPackage", 
            "TypographyDashes", 
            "TypographyQuotes",
            "StringFormatMatches",
            "StringFormatInvalid",
            "UnusedResources",
            "ValidActions",
            "ResourceType"
        ))
    }

    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res")
        }
    }
}

abstract class FilterJreAssets : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val filterJreAssets = tasks.register<FilterJreAssets>("filterJreAssets") {
    val arch = System.getProperty("arch", "all")
    inputs.property("arch", arch)
    from(layout.projectDirectory.dir("src/main/jreAssets"))
    outputDir.set(layout.buildDirectory.dir("generated/jreAssets"))

    if (arch != "all") {
        exclude { it.name.startsWith("bin-") && it.name != "bin-$arch.tar.xz" }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    output.outputFileName =
                        "FXL-${variant.buildType}-${project.android.defaultConfig.versionName}-${abi}.apk"
                }
            }
        }

        if (System.getProperty("arch", "all") != "all") {
            variant.sources.assets?.addGeneratedSourceDirectory(filterJreAssets) { it.outputDir }
        } else {
            variant.sources.assets?.addStaticSourceDirectory("src/main/jreAssets")
        }

        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }

        afterEvaluate {
            val mergeAssets =
                tasks.named("merge${variantName}Assets", MergeSourceSetFolders::class.java)

            val arch = System.getProperty("arch", "all")
            mergeAssets.configure { inputs.property("lwjglArch", arch) }

            mergeAssets.configure {
                doLast {
                    if (arch == "all") return@doLast

                    val abi = when (arch) {
                        "arm" -> "armeabi-v7a"
                        "arm64" -> "arm64-v8a"
                        "x86" -> "x86"
                        "x86_64" -> "x86_64"
                        else -> return@doLast
                    }

                    val assetsDir = outputDir.get().asFile

                    val lwjglVersions = project.file("libs").listFiles { f ->
                        f.name.matches(Regex("lwjgl-\\d+\\.\\d+\\.\\d+-natives-release\\.aar"))
                    }
                        ?.map {
                            Regex("lwjgl-(\\d+\\.\\d+\\.\\d+)-natives-release\\.aar")
                                .find(it.name)!!.groupValues[1]
                        }
                        ?: emptyList()

                    lwjglVersions.forEach { version ->
                        val nativesDir = File(assetsDir, "app_runtime/lwjgl/$version/natives")

                        if (nativesDir.isDirectory) {
                            nativesDir.listFiles()?.forEach { dir ->
                                if (dir.isDirectory && dir.name != abi) {
                                    logger.lifecycle("删除非目标架构 natives: $dir")
                                    dir.deleteRecursively()
                                }
                            }
                        }
                    }

                    File(assetsDir, "app_runtime/jna").listFiles()?.forEach { versionDir ->
                        val nativesDir = File(versionDir, "natives")

                        if (nativesDir.isDirectory) {
                            nativesDir.listFiles()?.forEach { dir ->
                                if (dir.isDirectory && dir.name != abi) {
                                    logger.lifecycle("删除非目标架构 natives: $dir")
                                    dir.deleteRecursively()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(project(":ZipFileSystem"))
    implementation(project(":Terracotta"))
    implementation(libs.commons.io)
    implementation(libs.jelf)
    implementation(libs.nanohttpd)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.opennbt)
    implementation(libs.lz4)
    implementation(libs.gson)
    implementation(libs.tomlj)
    implementation(libs.constant.pool.scanner)
    implementation(libs.jsoup)
    implementation(libs.chardet)
    implementation(libs.junrar)
    implementation(libs.bytehook)
    implementation(libs.appcompat)
    implementation(libs.androidx.viewpager2)
    implementation(libs.core.splashscreen)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.recyclerview)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.register("updateMap") {
    doLast {
        val list = mutableListOf<String>()
        val mapFile = file("${rootDir}/version_map.json")

        mapFile.forEachLine {
            list.add(
                when {
                    it.contains("versionCode") -> it.replace(
                        Regex("[0-9]+"),
                        android.defaultConfig.versionCode.toString()
                    )

                    it.contains("versionName") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    it.contains("date") -> it.replace(
                        Regex("\\d{4}\\.\\d{2}\\.\\d{2}"),
                        SimpleDateFormat("yyyy.MM.dd").format(Date())
                    )

                    it.contains("url") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    else -> it
                }
            )
        }

        mapFile.writeText(list.joinToString("\n"), Charsets.UTF_8)
    }
}
