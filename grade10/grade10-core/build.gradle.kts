plugins {
    kotlin("jvm")
}

val versionKotlin: String by extra
val versionKryo: String by extra

dependencies {
    implementation(kotlin("stdlib", versionKotlin))

    api("com.esotericsoftware", "kryo", versionKryo)
}