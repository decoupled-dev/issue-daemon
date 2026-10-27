plugins {
    id("com.android.application")
}

android {
    namespace = "com.aaos.issuedaemon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aaos.issuedaemon"
        minSdk = 34 // Android 14
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Car APIs (VIN, car service lifecycle). Not required at runtime on non-automotive builds.
    useLibrary("android.car", false)

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.jar stubs (Log, Process.myPid) return defaults in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Privileged permissions are expected: this app ships as a platform-signed priv-app.
        disable += setOf("ProtectedPermissions", "ExpiredTargetSdkVersion")
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (android.jar has stubs)
}
