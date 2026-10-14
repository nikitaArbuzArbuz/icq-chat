plugins {
    java
    application
}

group = "com.icqchat"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.liquibase:liquibase-core:4.30.0")

    implementation("com.auth0:java-jwt:4.4.0")
    implementation("at.favre.lib:bcrypt:0.10.2")

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

application {
    mainClass.set("com.icqchat.App")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
