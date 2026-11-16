import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0")
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.apache.poi)
    implementation(libs.apache.poi.ooxml)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.example.vitazonebussinessautomation.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.example.vitazonebussinessautomation"
            // каждая сборка в GitHub Actions получает новую версию, иначе Windows не обновляет установленную программу
            packageVersion = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

            windows {
                // постоянный UUID: новый .msi обновляет уже установленную версию, а не ставит рядом
                upgradeUuid = "6f1d2b7a-4c3e-4b8f-9a2d-5e7c1f3b8a90"
                menuGroup = "Vitazone"
                // установщик спрашивает папку (можно ставить в C:\Vitazone)
                dirChooser = true
                shortcut = true
            }
        }
    }
}