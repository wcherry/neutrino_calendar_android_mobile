// Calendar's model, rules and services: events and their recurrence, reminders, tasks and Smart
// Add, calendars and holidays, saved places, offline writes and sync — everything that has to agree
// with the web and the iOS app case for case. Plain Kotlin/JVM, so it is tested on the JVM against
// the same generated vectors the iOS app uses, and against an in-memory server.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.neutrino.shared:neutrino-core")
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    // date-holidays, the web's own holiday rules, runs in Rhino: no JNI, so the JVM tests run it too.
    implementation(libs.rhino)

    testImplementation(testFixtures("com.neutrino.shared:neutrino-core"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The vectors are pinned to a zone of their own; this keeps anything left to the default honest.
    systemProperty("user.timezone", "Europe/Berlin")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
