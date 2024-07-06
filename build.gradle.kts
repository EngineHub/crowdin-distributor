import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention

plugins {
    java
    application
    `maven-publish`
    id("org.cadixdev.licenser") version "0.6.1"
    id("net.researchgate.release") version "3.0.2"
    id("com.jfrog.artifactory") version "5.2.2"
}

license {
    ext {
        set("name", project.name)
        set("organization", project.property("organization"))
        set("url", project.property("url"))
    }
    header(rootProject.file("HEADER.txt"))
}

release {
    tagTemplate = "v\${version}"
    buildTasks = listOf<String>()
}

val javaVersion = JavaVersion.VERSION_21

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion.majorVersion))
    }
}

java {
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:24.1.0")

    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.techshroom:greenish-jungle:0.0.3")
    implementation("org.jfrog.artifactory.client:artifactory-java-client-services:2.17.0")
    implementation("com.vdurmont:semver4j:3.1.0")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.17.2"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.3")
}

application {
    mainClass.set("org.enginehub.crowdin.Main")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

configure<PublishingExtension> {
    publications {
        register<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])
            artifact(tasks.named("distZip")) {
                classifier = "bundle"
            }
        }
    }
}


val ARTIFACTORY_CONTEXT_URL = "artifactory_contextUrl"
val ARTIFACTORY_USER = "artifactory_user"
val ARTIFACTORY_PASSWORD = "artifactory_password"

if (!project.hasProperty(ARTIFACTORY_CONTEXT_URL)) ext[ARTIFACTORY_CONTEXT_URL] = "http://localhost"
if (!project.hasProperty(ARTIFACTORY_USER)) ext[ARTIFACTORY_USER] = "guest"
if (!project.hasProperty(ARTIFACTORY_PASSWORD)) ext[ARTIFACTORY_PASSWORD] = ""

configure<ArtifactoryPluginConvention> {
    setContextUrl("${project.property(ARTIFACTORY_CONTEXT_URL)}")
    clientConfig.publisher.run {
        repoKey = when {
            "${project.version}".contains("SNAPSHOT") -> "libs-snapshot-local"
            else -> "libs-release-local"
        }
        username = "${project.property(ARTIFACTORY_USER)}"
        password = "${project.property(ARTIFACTORY_PASSWORD)}"
        isMaven = true
        isIvy = false
    }
}

tasks.artifactoryPublish {
    publications("maven")
}
