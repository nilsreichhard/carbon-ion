import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
}

android {
    namespace = "com.nilsreichhard.carbonion.widget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nilsreichhard.carbonion.widget"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.1.8"
    }

    /*
     * Release signing from a keystore kept OUTSIDE the repo. Default location
     * ~/.carbon-ion-keys/keystore.properties (storeFile, storePassword, keyAlias,
     * keyPassword); override with -Pcarbon.signing=/abs/path/keystore.properties.
     * Without that file the release APK is built unsigned.
     */
    val signingProps = (findProperty("carbon.signing") as String?
        ?: "${System.getProperty("user.home")}/.carbon-ion-keys/keystore.properties").let(::file)
    val releaseSigning = if (signingProps.isFile) {
        val p = Properties().apply { signingProps.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    } else null

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // Signed when the external keystore exists (see signingConfigs); shrunk, never debuggable.
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            /* Preview from another payload: -Pcarbon.payload=/abs/file.json -Pcarbon.previewName=-sunny */
            all {
                it.systemProperty("carbon.payload", providers.gradleProperty("carbon.payload").getOrElse(""))
                it.systemProperty("carbon.city", providers.gradleProperty("carbon.city").getOrElse(""))
                it.systemProperty("carbon.previewName", providers.gradleProperty("carbon.previewName").getOrElse(""))
                /* Rewrite the committed picker previews: -Pcarbon.updatePreview=true (WidgetPreviewTest) */
                it.systemProperty("carbon.updatePreview", providers.gradleProperty("carbon.updatePreview").getOrElse(""))
                /* QuickJS JNI library for the JS tests (-Pcarbon.quickjs=/path); -Pcarbon.skipQuickJs=true lets them skip */
                it.systemProperty("carbon.quickjs", providers.gradleProperty("carbon.quickjs").getOrElse(""))
                it.systemProperty("carbon.skipQuickJs", providers.gradleProperty("carbon.skipQuickJs").getOrElse(""))
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    // 1.1.0: next phone alarm to the watch face (Core Devices Pebble app, PebbleKit Android 2)
    // 1.2.0: the last release that builds against compileSdk 36 (1.3.x needs 37); same send/listen API.
    implementation("io.rebble.pebblekit2:client:1.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.test:core:1.7.0")
}

/*
 * Bundles the watchface's own PebbleKit JS weather code (../../src/pkjs) into
 * an APK asset on every build, so the widget always runs the same provider
 * pipeline as the watch. See ../tools/bundle-pkjs.js and ../README.md.
 */
abstract class BundlePkjs : DefaultTask() {
    @get:InputDirectory
    abstract val pkjsDir: DirectoryProperty

    @get:InputFiles
    abstract val toolFiles: ConfigurableFileCollection

    /** The watch's IcoMoon icon font (resources/fonts), copied as an asset. */
    @get:InputFile
    abstract val iconFont: RegularFileProperty

    @get:Input
    abstract val nodeBinary: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun bundle() {
        val script = toolFiles.files.first { it.name == "bundle-pkjs.js" }
        val out = outputDir.get().file("carbon-weather.js").asFile
        execOps.exec {
            commandLine(nodeBinary.get(), script.absolutePath, out.absolutePath)
        }
        iconFont.get().asFile.copyTo(outputDir.get().file("IcoMoon-Regular.ttf").asFile, overwrite = true)
    }
}

val bundlePkjs = tasks.register<BundlePkjs>("bundlePkjs") {
    pkjsDir.set(rootProject.layout.projectDirectory.dir("../src/pkjs"))
    iconFont.set(rootProject.layout.projectDirectory.file("../resources/fonts/IcoMoon-Regular.ttf"))
    toolFiles.from(
        rootProject.layout.projectDirectory.file("tools/bundle-pkjs.js"),
        rootProject.layout.projectDirectory.file("tools/widget-entry.js"),
    )
    /* Gradle daemons often miss Homebrew's PATH: -Pcarbon.node=/path/to/node overrides. */
    nodeBinary.set(
        providers.gradleProperty("carbon.node").orElse(
            providers.provider {
                listOf("/opt/homebrew/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() } ?: "node"
            }
        )
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundlePkjs, BundlePkjs::outputDir)
    }
}
