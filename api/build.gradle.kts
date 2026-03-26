// api module - pure Java interfaces plus SLF4J API
plugins {
    `java-library`
    `maven-publish`
    id("org.gradlex.extra-java-module-info") version "1.11"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "bot-api"
        }
    }
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.16")
    api(files("${rootProject.projectDir}/libs/ClaudePathfinder-1.0.0.jar"))
    // Gson needed at runtime by ClaudePathfinder for loading transitions/teleports JSON
    implementation("com.google.code.gson:gson:2.11.0")
}

extraJavaModuleInfo {
    automaticModule("ClaudePathfinder-1.0.0.jar", "claude.pathfinder")
}

tasks.javadoc {
    title = "BotWithUs API $version"

    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("html5", true)
        encoding = "UTF-8"
        charSet = "UTF-8"
        memberLevel = JavadocMemberLevel.PUBLIC
        tags("apiNote:a:API Note:")
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }

    // Exclude module-info from source so javadoc runs in package mode
    exclude("module-info.java")

    // Treat warnings as non-fatal during Javadoc generation
    isFailOnError = false
}
