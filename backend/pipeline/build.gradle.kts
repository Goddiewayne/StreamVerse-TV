plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "com.streamverse.pipeline"
version = "2.0.0"

repositories {
    mavenCentral()
}

val okhttpVersion = "4.12.0"
val gsonVersion = "2.11.0"
val coroutinesVersion = "1.9.0"
val slf4jVersion = "2.0.16"
val logbackVersion = "1.5.14"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
}

application {
    mainClass.set("com.streamverse.pipeline.PipelineAppKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_21.toString()
    targetCompatibility = JavaVersion.VERSION_21.toString()
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "com.streamverse.pipeline.PipelineAppKt"
    }
}
