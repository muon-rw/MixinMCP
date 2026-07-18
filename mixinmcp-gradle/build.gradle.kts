plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
    id("maven-publish")
}

group = "dev.mixinmcp"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0-SNAPSHOT")

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
