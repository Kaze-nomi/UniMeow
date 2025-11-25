plugins {
    jacoco
}

repositories {
    mavenCentral()
}

tasks.register<JacocoReport>("jacocoCombinedReport") {

    group = "verification"
    description = "Combines JaCoCo coverage reports from all subprojects"

    dependsOn(subprojects.map { ":${it.name}:jacocoTestReport" })
    
    dependsOn(subprojects.map { ":${it.name}:test" })

    executionData.setFrom(
        subprojects.map { 
            it.tasks.getByName<JacocoReport>("jacocoTestReport").executionData 
        }
    )

    classDirectories.setFrom(
        subprojects.map { 
            it.tasks.getByName<JacocoReport>("jacocoTestReport").classDirectories 
        }
    )

    reports {
        html.required = true
    }
}