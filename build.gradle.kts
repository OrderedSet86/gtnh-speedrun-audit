
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// Forward -Pselftest=<dir> into the dev server as the self-test activation property:
//   ./gradlew runServer -Pselftest=run/selftest
tasks.named("runServer", JavaExec::class.java) {
    (project.findProperty("selftest") as String?)?.let { systemProperty("speedrunaudit.selftest", it) }
}
