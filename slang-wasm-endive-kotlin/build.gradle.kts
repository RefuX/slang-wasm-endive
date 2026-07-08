plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

// Track the root project's group/version so the two artifacts are always
// released together rather than drifting independently.
group = rootProject.group
version = rootProject.version
description = "Kotlin DSL builders for slang-wasm-endive"

kotlin {
    jvmToolchain(11)
}

repositories {
    mavenCentral()
}

dependencies {
    // The Kotlin DSL is a thin layer over the Java API; consumers of this jar
    // get the Java jar transitively.
    api(project(":"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// ── Maven Central publishing (com.vanniktech.maven.publish) ─────────────────
// Publishes io.github.refux:slang-wasm-endive-kotlin alongside the root jar it
// depends on. Credentials/signing come from the same Gradle properties as the
// root module (set in CI as env vars — see release.yml).
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "slang-wasm-endive-kotlin"
        description = "Kotlin DSL builders over the slang-wasm-endive Java API."
        inceptionYear = "2026"
        url = "https://github.com/RefuX/slang-wasm-endive"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "RefuX"
                name = "James Roome"
                url = "https://github.com/RefuX"
            }
        }
        scm {
            url = "https://github.com/RefuX/slang-wasm-endive"
            connection = "scm:git:git://github.com/RefuX/slang-wasm-endive.git"
            developerConnection = "scm:git:ssh://git@github.com/RefuX/slang-wasm-endive.git"
        }
    }
}
