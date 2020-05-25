plugins {
    base
    kotlin("jvm") version "1.3.72"
}

allprojects {
    group = "science.atlarge.grade10"
    version = "0.1-SNAPSHOT"

    repositories {
        jcenter()
    }

    extra["versionKotlin"] = "1.3.72"
    extra["versionKryo"] = "4.0.0"
}
