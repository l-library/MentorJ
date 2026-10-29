import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    `java`
    checkstyle
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation(libs.tika.core) {
        // Tika core uses commonmark only for Markdown; nothing here parses Markdown.
        exclude(group = "org.commonmark")
    }
    implementation(libs.tika.pdf) {
        // Tika's PDF module declares BouncyCastle (for signed PDFs), pdfbox-tools, picocli and
        // commonmark for its command line tools. Text extraction never reaches them: the whole
        // course corpus parses into the same 218 chunks with these jars removed, while they
        // duplicated thousands of classes the platform already ships.
        exclude(group = "org.bouncycastle")
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
        exclude(group = "info.picocli")
        exclude(group = "org.commonmark")
    }

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            // Verify against the target IDE declared above instead of the latest release,
            // which keeps the result deterministic and reuses the already-downloaded IDE.
            current()
        }
    }
}
