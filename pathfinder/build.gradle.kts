plugins {
    id("org.gradlex.extra-java-module-info") version "1.11"
}

base {
    archivesName = "Pathfinder"
}

val imguiVersion = "1.90.0"

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly("io.github.spair:imgui-java-binding:$imguiVersion")
}

extraJavaModuleInfo {
    automaticModule("org.msgpack:msgpack-core", "msgpack.core")
}

// Copy the built JAR into the scripts/ directory for the runtime to discover
tasks.register<Copy>("installScript") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(rootProject.layout.projectDirectory.dir("scripts"))
}

tasks.named("build") {
    finalizedBy("installScript")
}
