
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// Forward the harness activation properties into the dev server:
//   ./gradlew runServer -Pselftest=run/selftest
//   ./gradlew runServer -Pae2bench=bench-out [-Pae2benchSizes=1000,50000] [-Pae2benchNbtPercent=20]
tasks.named("runServer", JavaExec::class.java) {
    (project.findProperty("selftest") as String?)?.let { systemProperty("speedrunaudit.selftest", it) }
    (project.findProperty("ae2bench") as String?)?.let { systemProperty("speedrunaudit.ae2bench", it) }
    (project.findProperty("ae2benchSizes") as String?)?.let {
        systemProperty("speedrunaudit.ae2bench.sizes", it)
    }
    (project.findProperty("ae2benchNbtPercent") as String?)?.let {
        systemProperty("speedrunaudit.ae2bench.nbtPercent", it)
    }
}
