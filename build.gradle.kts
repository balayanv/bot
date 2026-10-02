plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

kotlin {
    // Native CLI targets: mac, linux, windows (cross-compiled from any host).
    macosArm64 { binaries.executable { entryPoint = "main" } }
    macosX64 { binaries.executable { entryPoint = "main" } }
    linuxX64 { binaries.executable { entryPoint = "main" } }
    linuxArm64 { binaries.executable { entryPoint = "main" } }
    mingwX64 { binaries.executable { entryPoint = "main" } }

    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.6.0")
            implementation("io.ktor:ktor-client-curl:3.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        all { languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi") }
    }
}
