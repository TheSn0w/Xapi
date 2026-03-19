val imguiVersion = "1.90.0"
val javafxVersion = "24"
val javafxPlatform = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "win"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "mac"
    else -> "linux"
}

base {
    archivesName = "Xapi"
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    compileOnly("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    compileOnly("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
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
