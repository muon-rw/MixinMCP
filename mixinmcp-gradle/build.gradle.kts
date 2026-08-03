plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
    id("maven-publish")
}

group = "dev.mixinmcp"

// Same single version source as the root build: the committed Claude plugin manifest.
version = providers
    .fileContents(layout.settingsDirectory.file("claude-plugin/.claude-plugin/plugin.json"))
    .asText
    .map { text ->
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: throw GradleException("No version field in claude-plugin/.claude-plugin/plugin.json")
    }
    .get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("org.vineflower:vineflower:1.12.0")
    implementation(gradleApi())
    implementation("com.google.code.gson:gson:2.14.0")
}

gradlePlugin {
    plugins {
        create("mixinDecompile") {
            id = "dev.mixinmcp.decompile"
            implementationClass = "dev.mixinmcp.gradle.MixinDecompilePlugin"
        }
    }
}

// Read back at runtime as the pluginVersion stamped into each manifest.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

publishing {
    repositories {
        maven {
            name = "muon"
            url = uri("https://maven.muon.rip/releases")
            credentials {
                username = providers.environmentVariable("MAVEN_USERNAME").orNull
                password = providers.environmentVariable("MAVEN_PASSWORD").orNull
            }
        }
    }
}
