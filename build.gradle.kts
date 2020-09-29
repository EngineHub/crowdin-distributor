plugins {
    java
    application
    id("net.minecrell.licenser") version "0.4.1"
}

license {
    ext {
        set("name", project.name)
        set("organization", project.property("organization"))
        set("url", project.property("url"))
    }
    header = rootProject.file("HEADER.txt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_15
    targetCompatibility = sourceCompatibility
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.release.set(JavaVersion.VERSION_15.majorVersion.toInt())
    options.compilerArgs = listOf("--enable-preview")
}

repositories {
    jcenter()
    maven {
        name = "Sonatype Snapshots"
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
        mavenContent {
            snapshotsOnly()
        }
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:20.1.0")

    implementation("com.google.guava:guava:29.0-jre")
    implementation("com.squareup.okhttp3:okhttp:4.9.0")
    implementation("com.techshroom:greenish-jungle:0.0.3")

    implementation(platform("com.fasterxml.jackson:jackson-bom:2.12.0-SNAPSHOT"))
    implementation("com.fasterxml.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")
}

application {
    mainClassName = "org.enginehub.crowdin.Main"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
