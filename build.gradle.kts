/*
 * Copyright Exafunction, Inc.
 */

import com.google.protobuf.gradle.*
import java.nio.charset.StandardCharsets
import java.util.*

buildscript {
  repositories { mavenCentral() }
}

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.7.10"
  id("org.jetbrains.intellij") version "1.11.0"

  // protobuf
  id("com.google.protobuf") version "0.8.18"
}

group = "com.codeium"
// Do not manually edit.
version = "1.6.13"

repositories {
  mavenCentral()
  google()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
  version.set("2023.1.2")
  type.set("IC") // Target IDE Platform

  plugins.set(listOf(/* Plugin Dependencies */ ))
  updateSinceUntilBuild.set(false)
}

val grpcVersion = "1.50.2"
val grpcKotlinVersion = "1.3.0"
val protobufVersion = "3.21.9"
val coroutinesVersion = "1.6.4"

dependencies {
  // GRPC
  implementation(kotlin("stdlib"))
  implementation("javax.annotation:javax.annotation-api:1.3.2")
  implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
  implementation("io.grpc:grpc-protobuf:$grpcVersion")
  implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
  implementation("com.googlecode.json-simple:json-simple:1.1.1")
  implementation("com.google.guava:guava:31.1-jre")
  runtimeOnly("io.grpc:grpc-netty-shaded:$grpcVersion")

  // WebSocket
  implementation("com.squareup.okhttp3:okhttp:4.11.0")
}

tasks {
  exec {
    workingDir(".")
    commandLine("./proto.sh")
  }
}

// Enterprise build configuration
tasks.register("enterpriseBuildBefore") {
  val enterpriseEnv = System.getenv("ENTERPRISE")
  if (enterpriseEnv != null) {
    // Backup the current plugin.xml file
    val original = File("src/main/resources/META-INF/plugin.xml")
    val backup = File("src/main/resources/META-INF/plugin.xml.bak")
    if (original.exists()) {
      original.copyTo(backup, overwrite = true)
    }
    // Replace the plugin.xml file with the enterprise XML file
    val enterprise = File("src/main/resources/META-INF/enterprise.xml")
    if (enterprise.exists()) {
      enterprise.copyTo(original, overwrite = true)
    }
    println("plugin.xml replaced with enterprise.xml")
  }
}

// Enterprise build restore
tasks.register("enterpriseBuildAfter") {
  val enterpriseEnv = System.getenv("ENTERPRISE")
  if (enterpriseEnv != null) {
    doLast {
      // Restore the original plugin.xml file
      val original = File("src/main/resources/META-INF/plugin.xml.bak")
      if (original.exists()) {
        original.copyTo(File("src/main/resources/META-INF/plugin.xml"), overwrite = true)
      }
      val backup = File("src/main/resources/META-INF/plugin.xml.bak")
      if (backup.exists()) {
        backup.delete()
      }
      println("plugin.xml restored")
    }
  }
}

tasks.build.get().dependsOn(tasks.getByName("enterpriseBuildBefore"))

tasks.getByName("buildPlugin").finalizedBy(tasks.getByName("enterpriseBuildAfter"))

tasks {
  run {
    // workaround for https://youtrack.jetbrains.com/issue/IDEA-285839/Classpath-clash-when-using-coroutines-in-an-unbundled-IntelliJ-plugin
    buildPlugin {
      exclude { "coroutines" in it.name }
    }
    prepareSandbox {
      exclude { "coroutines" in it.name }
    }
  }
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "11"
    targetCompatibility = "11"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> { kotlinOptions.jvmTarget = "17" }

  patchPluginXml { sinceBuild.set("223") }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin { token.set(System.getenv("PUBLISH_TOKEN")) }

  signPlugin {
    val certChainEnv = System.getenv("CERTIFICATE_CHAIN")
    val privateKeyEnv = System.getenv("PRIVATE_KEY")
    val passwordEnv = System.getenv("PRIVATE_KEY_PASSWORD")
    if (certChainEnv != null && privateKeyEnv != null && passwordEnv != null) {
      certificateChain.set(String(Base64.getDecoder().decode(certChainEnv), StandardCharsets.UTF_8))
      privateKey.set(String(Base64.getDecoder().decode(privateKeyEnv), StandardCharsets.UTF_8))
      password.set(passwordEnv)
    }
  }

  publishPlugin {
    val tokenEnv = System.getenv("PUBLISH_TOKEN")
    if (tokenEnv != null) {
      token.set(tokenEnv)
    }
  }
}

configurations {
  all {
    // Allows using project dependencies instead of IDE dependencies during compilation and test
    // running
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
  }
}
