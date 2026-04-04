plugins {
    id("org.gradlex.extra-java-module-info") version "1.11"
}

val imguiVersion = "1.90.0"
val javafxVersion = "24"
val javafxPlatform = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "win"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "mac"
    else -> "linux"
}

base {
    archivesName = "Woodcutting"
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    compileOnly("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
}

// Copy the built script JAR into the scripts/ directory for the runtime to discover
tasks.register<Copy>("installScript") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(rootProject.layout.projectDirectory.dir("scripts"))
}

tasks.named("build") {
    finalizedBy("installScript")
}
