allprojects {
    dependencyLocking { lockAllConfigurations() }
    repositories {
        google()
        mavenCentral()
    }
}

val outputDirectory = rootProject.layout.projectDirectory.dir("../build")
rootProject.layout.buildDirectory.set(outputDirectory)
subprojects {
    layout.buildDirectory.set(outputDirectory.dir(name))
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
