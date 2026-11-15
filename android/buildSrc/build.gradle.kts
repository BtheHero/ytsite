plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle-api:8.7.3")
    implementation("org.ow2.asm:asm:9.7.1")
}
