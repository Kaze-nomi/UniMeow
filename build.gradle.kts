plugins {
    jacoco
    id("com.diffplug.spotless") version "8.2.1" apply false
    id("com.github.spotbugs") version "6.5.1" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    if (path != ":gRPC") {
        pluginManager.withPlugin("java") {
            apply(plugin = "com.diffplug.spotless")
            apply(plugin = "pmd")
            apply(plugin = "com.github.spotbugs")

            extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
                java {
                    eclipse()
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }

            extensions.configure<PmdExtension> {
                toolVersion = "7.19.0"
                isIgnoreFailures = true
                ruleSets = listOf()
                ruleSetFiles = files("${rootDir}/config/pmd/ruleset.xml")
            }

            extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
                toolVersion = "4.9.8"
                ignoreFailures = true
                effort = com.github.spotbugs.snom.Effort.DEFAULT
                reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
                excludeFilter.set(file("${rootDir}/config/spotbugs/exclude.xml"))
            }
        }
    }
}
