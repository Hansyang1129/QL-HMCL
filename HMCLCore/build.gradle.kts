plugins {
    `java-library`
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.compileJava {
    options.compilerArgs.add("--add-exports=jdk.attach/sun.tools.attach=ALL-UNNAMED")
}

dependencies {
    api(libs.kala.compress.zip)
    api(libs.kala.compress.tar)
    api(libs.simple.png.javafx)
    api(libs.gson)
    api(libs.tomlj)
    api(libs.xz)
    api(libs.lz4)
    api(libs.fx.gson)
    api(libs.constant.pool.scanner)
    api(libs.nanohttpd)
    api(libs.jsoup)
    api(libs.chardet)
    api(libs.jna)
    api(libs.pci.ids)
    api(libs.hello.nbt)
    api(libs.weburl)
    api(libs.uuid.tools)
    compileOnlyApi(libs.jetbrains.annotations)

    testImplementation(libs.jna.platform)
    testImplementation(libs.jimfs)
}

tasks.register<JavaExec>("runQlManifestTool") {
    group = "ql"
    description = "Run the Qinglan manifest publishing tool (org.jackhuang.hmcl.ql.QLManifestTool)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.jackhuang.hmcl.ql.QLManifestTool"
    doFirst {
        val qlToolArgs = (project.findProperty("qlToolArgs") as String?)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        args(qlToolArgs)
    }
}

tasks.register<JavaExec>("runQlSyncE2E") {
    group = "ql"
    description = "Run the Qinglan sync end-to-end driver (org.jackhuang.hmcl.ql.QLSyncE2E) against a real mrpack."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.jackhuang.hmcl.ql.QLSyncE2E"
    jvmArgs("-Xmx1g")
    doFirst {
        val syncE2EArgs = (project.findProperty("syncE2EArgs") as String?)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        args(syncE2EArgs)
    }
}

tasks.processResources {
    listOf(
        "HMCLTransformerDiscoveryService",
        "HMCLMultiMCBootstrap"
    ).map { project(":$it").tasks["jar"] as Jar }.forEach { task ->
        dependsOn(task)

        into("assets/game") {
            from(task.outputs.files)
        }
    }
}
