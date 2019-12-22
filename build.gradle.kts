import java.util.concurrent.Callable

plugins {
    `java-library`
    signing
    `maven-publish`
    id("uk.jamierocks.propatcher") version "1.3.1"
    id("net.minecrell.licenser") version "0.4.1"
    id("com.github.johnrengelman.shadow") version "5.2.0"
}

val artifactId = name.toLowerCase()
base.archivesBaseName = artifactId

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

configurations {
    register("jdt") {
        isTransitive = false
    }
}

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://maven.fabricmc.net/")
}

val jdt = "org.eclipse.jdt:org.eclipse.jdt.core:3.19.0"
dependencies {
    api(jdt)

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-SNAPSHOT")
    api("org.cadixdev:lorenz:0.5.0")

    "jdt"("$jdt:sources")

    runtime("net.fabricmc:tiny-remapper:0.2.1.61")
}

tasks.withType<Javadoc> {
    exclude("org.cadixdev.$artifactId.jdt.".replace('.', '/'))
}

// Patched ImportRewrite from JDT
patches {
    patches = file("patches")
    root = file("build/jdt/original")
    target = file("build/jdt/patched")
}
val jdtSrcDir = file("jdt")

val extract = task<Copy>("extractJdt") {
    dependsOn(configurations["jdt"])
    from(Callable { zipTree(configurations["jdt"].singleFile) })
    destinationDir = patches.root

    include("org/eclipse/jdt/core/dom/rewrite/ImportRewrite.java")
    include("org/eclipse/jdt/internal/core/dom/rewrite/imports/*.java")
}
tasks["applyPatches"].inputs.files(extract)

val renames = listOf(
        "org.eclipse.jdt.core.dom.rewrite" to "org.cadixdev.$artifactId.jdt.rewrite.imports",
        "org.eclipse.jdt.internal.core.dom.rewrite.imports" to "org.cadixdev.$artifactId.jdt.internal.rewrite.imports"
)

fun createRenameTask(prefix: String, inputDir: File, outputDir: File, renames: List<Pair<String, String>>): Task
        = task<Copy>("${prefix}renameJdt") {
    destinationDir = file(outputDir)

    renames.forEach { (old, new) ->
        from("$inputDir/${old.replace('.', '/')}") {
            into("${new.replace('.', '/')}/")
        }
    }

    filter { renames.fold(it) { s, (from, to) -> s.replace(from, to) } }
}

val renameTask = createRenameTask("", patches.target, jdtSrcDir, renames)
renameTask.inputs.files(tasks["applyPatches"])

tasks["makePatches"].inputs.files(createRenameTask("un", jdtSrcDir, patches.target, renames.map { (a,b) -> b to a }))
sourceSets["main"].java.srcDirs(renameTask)

tasks.withType<Test> {
    useJUnitPlatform()
}

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar = task<Jar>("javadocJar") {
    classifier = "javadoc"
    from(tasks["javadoc"])
}


val shadowJar: ShadowJar by tasks
shadowJar.apply {
    relocate("org.objectweb.asm", "net.fabricmc.tinyremapper.asm")
    relocate("org.eclipse", "org.cadixdev.shadow.eclipse")
}

artifacts {
    add("archives", sourceJar)
    add("archives", javadocJar)
    add("archives", shadowJar)
}

license {
    header = file("HEADER")
    exclude("org.cadixdev.$artifactId.jdt.".replace('.', '/'))
}

val isSnapshot = version.toString().endsWith("-SNAPSHOT")

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = base.archivesBaseName

            artifact(sourceJar)
            artifact(javadocJar)

            pom {
                val url: String by project
                url(url)

                scm {
                    url(url)
                    connection("scm:git:$url.git")
                    developerConnection.set(connection)
                }

                issueManagement {
                    system("GitHub Issues")
                    url("$url/issues")
                }

                licenses {
                    license {
                        name("Eclipse Public License, Version 2.0")
                        url("https://www.eclipse.org/legal/epl-2.0/")
                        distribution("repo")
                    }
                }
            }
        }
    }

    repositories {
        val sonatypeUsername: String? by project
        val sonatypePassword: String? by project
        if (sonatypeUsername != null && sonatypePassword != null) {
            val url = if (isSnapshot) "https://oss.sonatype.org/content/repositories/snapshots/"
                else "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
            maven(url) {
                credentials {
                    username = sonatypeUsername
                    password = sonatypePassword
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}

tasks.withType<Sign> {
    onlyIf { false }
}

operator fun Property<String>.invoke(v: String) = set(v)
