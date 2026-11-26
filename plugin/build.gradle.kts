import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.1"
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
        // JFlex for lexer generation
        instrumentationTools()
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

// JFlex lexer generation task
val generateLexer = tasks.register<JavaExec>("generateLexer") {
    group = "build"
    description = "Generates the VJava lexer from the .flex file"

    val flexFile = file("src/main/flex/VJavaLexer.flex")
    val outputDir = file("src/main/gen/de/swfdtf/vjava/plugin/lang")

    inputs.file(flexFile)
    outputs.dir(outputDir)

    classpath = configurations.detachedConfiguration(
        dependencies.create("de.jflex:jflex:1.9.1")
    )
    mainClass.set("jflex.Main")
    // JFlex 1.9.1 valid options only – no --generated_by, no --skel NONE
    args = listOf(
        "--nobak",
        "--encoding", "UTF-8",
        "-d", outputDir.absolutePath,
        flexFile.absolutePath
    )

    doFirst {
        outputDir.mkdirs()
    }
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
    dependsOn(generateLexer)
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnit()
}
