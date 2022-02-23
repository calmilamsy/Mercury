import java.util.concurrent.Callable

plugins {
    `java-library`
    `maven-publish`
    id("uk.jamierocks.propatcher") version "1.3.2"
    id("org.cadixdev.licenser") version "0.5.0"
    id("com.github.johnrengelman.shadow") version "6.1.0"
}

val artifactId = name.toLowerCase()
base.archivesBaseName = artifactId

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

configurations {
    register("jdtSources") {
        isTransitive = false
    }
    register("jdt")
}
configurations["api"].extendsFrom(configurations["jdt"])

configurations.all {
    resolutionStrategy {
        failOnNonReproducibleResolution()
    }
}

repositories {
    mavenCentral()
}

val jdtVersion = "org.eclipse.jdt:org.eclipse.jdt.core:3.26.0"
dependencies {
    // JDT pulls all of these deps in, however they do not specify the exact version to use so they can get updated without us knowing.
    // Depend specifically on these versions to prevent them from being updated under our feet.
    // The POM is also patched later on to as this strict versioning does not make it through.
    "jdt" (jdtVersion)
    "jdt" ("org.eclipse.platform:org.eclipse.compare.core:[3.6.1000]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.commands:[3.10.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.contenttype:[3.7.1000]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.expressions:[3.7.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.filesystem:[1.9.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.jobs:[3.11.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.resources:[3.15.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.core.runtime:[3.22.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.app:[1.5.100]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.common:[3.15.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.preferences:[3.8.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.equinox.registry:[3.10.200]")
    "jdt" ("org.eclipse.platform:org.eclipse.osgi:[3.16.300]")
    "jdt" ("org.eclipse.platform:org.eclipse.team.core:[3.9.0]")
    "jdt" ("org.eclipse.platform:org.eclipse.text:[3.12.0]")

    // TODO: Split in separate modules
    api("org.cadixdev:at:0.1.0-rc1")
    api("org.cadixdev:lorenz:0.5.7")

    "jdtSources"("$jdtVersion:sources")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.cadixdev:lorenz-io-jam:0.5.7")
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
    dependsOn(configurations["jdtSources"])
    from(Callable { zipTree(configurations["jdtSources"].singleFile) })
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

tasks.jar.configure {
    manifest.attributes(mapOf("Automatic-Module-Name" to "org.cadixdev.$artifactId"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}

tasks.jar {
    archiveClassifier.set("thin")
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
    configurations = listOf(project.configurations["jdt"])
}
tasks["build"].dependsOn(tasks.shadowJar)

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar = task<Jar>("javadocJar") {
    classifier = "javadoc"
    from(tasks["javadoc"])
}

artifacts {
    add("archives", sourceJar)
    add("archives", javadocJar)
    add("archives", tasks.shadowJar)
}

license {
    header = file("HEADER")
    exclude("org.cadixdev.$artifactId.jdt.".replace('.', '/'))
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
           from(components["java"])
            artifactId = base.archivesBaseName

            artifact(sourceJar)
            artifact(javadocJar)

            pom {
                val name: String by project
                val description: String by project
                val url: String by project
                name(name)
                description(description)
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

                developers {
                    developer {
                        id("jamierocks")
                        name("Jamie Mansfield")
                        email("jmansfield@cadixdev.org")
                        url("https://www.jamiemansfield.me/")
                        timezone("Europe/London")
                    }
                }

                withXml {
                    // I pray that im being stupid that this isn't what you have to put up with when using kotlin
                    (((asNode().get("dependencies") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList)
                            .removeIf { node ->
                                val group = ((((node as groovy.util.Node).get("groupId") as groovy.util.NodeList).first() as groovy.util.Node).value() as groovy.util.NodeList).first() as String;
                                group.startsWith("org.eclipse.")
                            }
                }
            }
        }
    }

    repositories {
        val maven_url: String? = System.getenv("MAVEN_URL")

        if (maven_url != null) {
            maven(url = maven_url) {
                val mavenPass: String? = System.getenv("MAVEN_PASSWORD")
                mavenPass?.let {
                    credentials {
                        username = System.getenv("MAVEN_USERNAME")
                        password = mavenPass
                    }
                }
            }
        }
    }
}

operator fun Property<String>.invoke(v: String) = set(v)
