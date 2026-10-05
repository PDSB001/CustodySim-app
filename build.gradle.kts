// 根构建脚本：只声明插件，不在这里配置任何模块。
//
// AGP 9 起内置 Kotlin 支持（默认开启），因此**不再声明** org.jetbrains.kotlin.android。
//
// 但内置的 KGP 版本是 2.2.10，而 MiuiX 0.9.3 的元数据由 Kotlin 2.4.0 编译，
// 低版本编译器会报 "Module was compiled with an incompatible version of Kotlin"。
// AGP 官方给出的抬高方式就是在顶层构建脚本里显式声明 KGP 的 classpath。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "9.4.1" apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Publish this archive alongside the APK built from the same working tree.
// Only Android build inputs are included; credentials and generated output are excluded.
tasks.register<Zip>("androidCorrespondingSource") {
    group = "distribution"
    description = "Package the complete Android application source for AGPL distribution"
    archiveFileName.set("CustodySim-Android-corresponding-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootDir) {
        into("CustodySim-app")
        // A source archive must not sweep up local Gradle homes or downloaded
        // inspection checkouts. Select build inputs rather than all local files.
        include("*.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "gradle/**")
        include("LICENSE", "LICENSES/**", "README-LICENSE.md", "README.md", "AGENTS.md", ".gitignore", ".gitattributes", "scripts/**", "docs/**")
        include("app/src/**", "app/*.gradle.kts", "app/*.pro", "app/lint*.xml")
        include("episteme-core/src/**", "episteme-core/*.gradle.kts", "episteme-core/LICENSE", "episteme-core/README.md", "episteme-core/UPSTREAM.json")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.idea/**")
        exclude("local.properties", "**/*.jks", "**/*.keystore", "**/*.pem", "**/*.p12", "**/*.iml", "**/.env*", "**/captures/**")
    }

}
