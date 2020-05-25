plugins {
    kotlin("jvm")
    application
}

application {
    mainClassName = "science.atlarge.grade10.cli.CliKt"
}

tasks {
    "run"(JavaExec::class) {
        standardInput = System.`in`
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    implementation(project(":grade10-core"))
    implementation(project(":grade10-examples"))
}