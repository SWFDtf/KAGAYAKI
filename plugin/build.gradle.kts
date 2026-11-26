import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.1"
    id("org.jetbrains.grammarkit") version "2022.3.2.2"
}

group = "de.swfdtf.vjava"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community 2024.3+
        intellijIdeaCommunity("2024.3")
        // Java plugin for Language Injection (JavaLanguage.INSTANCE)
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "vjava Language Support"
        version = "1.0.0"
        ideaVersion {
            sinceBuild = "243"
            // untilBuild not set → no upper bound restriction
        }
    }
    publishing {
        // token = providers.gradleProperty("publishToken")
    }
    signing {
        // Configure signing if needed
    }
}

// JFlex lexer generation task (via org.jetbrains.grammarkit)
tasks.generateLexer {
    sourceFile.set(file("src/main/flex/VJavaLexer.flex"))
    targetOutputDir.set(file("src/main/gen/de/swfdtf/vjava/plugin/lang"))
    purgeOldFiles.set(true)
}

// Alternative: use jflex plugin approach manually
// We'll include the generated lexer in the src/main/gen source set
sourceSets {
    main {
        java {
            srcDir("src/main/gen")
        }
    }
}

tasks.compileJava {
    dependsOn(tasks.generateLexer)
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnit()
}
