plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.18.2"
}

version = property("mod_version")!!
group = property("maven_group")!!
base.archivesName = property("archives_base_name") as String

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    // Shader translation (GLSL -> SPIR-V -> MSL). Same LWJGL version Minecraft ships, bundled jar-in-jar.
    val lwjgl = property("lwjgl_version")
    for (module in listOf("lwjgl-shaderc", "lwjgl-spvc")) {
        implementation("org.lwjgl:$module:$lwjgl")
        include("org.lwjgl:$module:$lwjgl")
        runtimeOnly("org.lwjgl:$module:$lwjgl:natives-macos-arm64")
        include("org.lwjgl:$module:$lwjgl:natives-macos-arm64")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val jniHeaders = layout.buildDirectory.dir("generated/jni")
val nativeLib = layout.buildDirectory.file("native/libmetalcraft.dylib")

tasks.compileJava {
    options.release = 21
    options.headerOutputDirectory = jniHeaders
}

// ponytail: single .mm compiled with clang directly; move to CMake if it grows past a handful of files.
val buildNative = tasks.register<Exec>("buildNative") {
    val sources = fileTree("src/main/native")
    val javaHome = javaToolchains.compilerFor(java.toolchain).map { it.metadata.installationPath.asFile.absolutePath }
    dependsOn(tasks.compileJava)
    inputs.files(sources, jniHeaders)
    outputs.file(nativeLib)
    doFirst {
        nativeLib.get().asFile.parentFile.mkdirs()
        commandLine(
            "xcrun", "clang++", "-std=c++20", "-fobjc-arc", "-O2",
            "-arch", "arm64", "-mmacosx-version-min=14.0", "-shared",
            "-framework", "Metal", "-framework", "QuartzCore", "-framework", "Cocoa",
            "-I${javaHome.get()}/include", "-I${javaHome.get()}/include/darwin",
            "-I${jniHeaders.get().asFile}",
            "-o", nativeLib.get().asFile.absolutePath,
            *sources.files.filter { it.extension == "mm" }.map { it.absolutePath }.toTypedArray(),
        )
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
    from(buildNative) { into("natives") }
}

loom {
    runs.named("client") {
        // Metal API validation turns silent GPU driver crashes into readable assertion messages during development.
        // -Pbench drops it, since validation costs frame time.
        if (!providers.gradleProperty("bench").isPresent) environmentVariable("MTL_DEBUG_LAYER", "1")
        // ./gradlew runClient -PautoScreenshot=200 saves a screenshot every 200 ticks (see MetalCraft).
        providers.gradleProperty("autoScreenshot").orNull?.let { property("metalcraft.autoScreenshot", it) }
        // ./gradlew runClient -PopenGL runs vanilla OpenGL with the same build, for A/B comparisons.
        if (providers.gradleProperty("openGL").isPresent) property("metalcraft.disable", "true")
    }
}
