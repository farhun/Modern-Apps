plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "description"
}

android {
    namespace = "io.github.farhun.office"

    defaultConfig {
        applicationId = "io.github.farhun.office"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native ODF formula engine + document CRDT (Rust). See office/src/main/rust/.
rustNativeLib("office_engine", "office")

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    runtimeOnly("io.github.michaldvorak-gemalto:jp2-android:1.0.4")

    // Provides a real XmlPullParser implementation for JVM unit tests (Android's is a stub).
    testImplementation(libs.kxml2)
}
