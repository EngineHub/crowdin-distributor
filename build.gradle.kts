import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention
import org.jfrog.gradle.plugin.artifactory.dsl.DoubleDelegateWrapper
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask

plugins {
    java
    application
    `maven-publish`
    id("org.cadixdev.licenser") version "0.6.0"
    id("net.researchgate.release") version "2.8.1"
    id("com.jfrog.artifactory") version "4.21.0"
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

val javaVersion = JavaVersion.VERSION_16

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
    compileOnly("org.jetbrains:annotations:21.0.1")

    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    implementation("com.techshroom:greenish-jungle:0.0.3")
    implementation("org.jfrog.artifactory.client:artifactory-java-client-services:2.9.2")
    implementation("com.vdurmont:semver4j:3.1.0")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.12.3"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    implementation("org.slf4j:slf4j-simple:1.7.30")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.2")
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

val ext = extensions.extraProperties
if (!project.hasProperty("artifactory_contextUrl"))
    ext["artifactory_contextUrl"] = "http://localhost"
if (!project.hasProperty("artifactory_user"))
    ext["artifactory_user"] = "guest"
if (!project.hasProperty("artifactory_password"))
    ext["artifactory_password"] = ""
configure<ArtifactoryPluginConvention> {
    publish(delegateClosureOf<PublisherConfig> {
        setContextUrl(project.property("artifactory_contextUrl"))
        setPublishIvy(false)
        setPublishPom(true)
        repository(delegateClosureOf<DoubleDelegateWrapper> {
            invokeMethod("setRepoKey", when {
                "SNAPSHOT" in project.version.toString() -> "libs-snapshot-local"
                else -> "libs-release-local"
            })
            invokeMethod("setUsername", project.property("artifactory_user"))
            invokeMethod("setPassword", project.property("artifactory_password"))
        })
        defaults(delegateClosureOf<ArtifactoryTask> {
            publications("maven")
            setPublishArtifacts(true)
        })
    })
}

