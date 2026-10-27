import java.io.File

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// JDK argument files on Windows are interpreted using the native code page. Gradle's
// test-worker classpath can therefore lose a Chinese workspace path despite UTF-8
// source encoding. Put just our compiled test/main classes in a host-temp JAR so
// the worker classpath remains ASCII on this development machine. Sources, reports
// and ordinary build outputs stay in the user-selected workspace.
val testRuntimeJar = tasks.register<Jar>("testRuntimeJar") {
    archiveFileName.set("imagepdf-core-test-runtime.jar")
    destinationDirectory.set(file(providers.gradleProperty("imagepdf.testTempDir").orElse(
        File(System.getProperty("java.io.tmpdir"),
            "imagepdf-core-tests-${rootProject.projectDir.absolutePath.hashCode().toUInt().toString(16)}").path
    ).get()))
    from(sourceSets["main"].output)
    from(sourceSets["test"].output)
}

tasks.test {
    dependsOn(testRuntimeJar)
    classpath = files(testRuntimeJar.flatMap { it.archiveFile }) + configurations.testRuntimeClasspath.get()
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register<JavaExec>("engineProbe") {
    group = "verification"
    description = "Generate numbered 100/300/500-page PDFs and host-only memory/time metrics"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("cn.local.imagepdf.core.EngineProbe")
    maxHeapSize = "128m"
    systemProperty("java.awt.headless", "true")
    args(rootProject.layout.buildDirectory.dir("engine-probe").get().asFile.absolutePath)
}
