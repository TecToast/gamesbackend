val logback_version: String by project
val mongo_version: String by project
val exposed_version: String by project
val hikari_version: String by project
val mariadb_version: String by project
val jda_version: String by project
val ktor_version: String by project
plugins {
    kotlin("jvm") version "2.1.0"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.cloud.tools.jib") version "3.4.4"
}

group = "de.tectoast"
version = "0.0.1"

application {
    mainClass.set("de.tectoast.games.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

jib {
    from {
        platforms {
            platform {
                os = "linux"
                architecture = "arm64"
            }
        }
    }
    to {
        image = "tectoast/gamesbackend"
    }
    container {
        mainClass = "de.tectoast.games.ApplicationKt"
    }

}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "de.tectoast.games.ApplicationKt"
    }
}

repositories {
    mavenCentral()
    maven(url = "https://maven.lavalink.dev/releases")
}

val ktorDependencies = listOf(
    // Client
    "ktor-client-core",
    "ktor-client-cio",
    "ktor-serialization-kotlinx-json",
    "ktor-client-content-negotiation",
    // Server
    "ktor-server-core",
    "ktor-server-cio",
    "ktor-server-auth",
    "ktor-server-sessions",
    "ktor-server-content-negotiation",
    "ktor-serialization-kotlinx-json",
    "ktor-server-cors",
    "ktor-server-call-logging",
    "ktor-server-call-logging-jvm",
    "ktor-server-websockets-jvm"

)

dependencies {
    ktorDependencies.forEach {
        implementation("io.ktor:$it:$ktor_version")
    }
//    implementation("org.mongodb:mongodb-driver-core:$mongo_version")
//    implementation("org.mongodb:mongodb-driver-sync:$mongo_version")
//    implementation("org.mongodb:bson:$mongo_version")
    implementation("net.dv8tion:JDA:$jda_version")
    implementation("club.minnced:jda-ktx:0.12.0")
    implementation("dev.arbjerg:lavaplayer:2.2.2")
    implementation("dev.lavalink.youtube:v2:1.11.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.litote.kmongo:kmongo-coroutine-serialization:$mongo_version")
    implementation("org.litote.kmongo:kmongo-id-serialization:$mongo_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0-RC")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadb_version")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    implementation("com.zaxxer:HikariCP:$hikari_version")
}
