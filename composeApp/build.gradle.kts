import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop")
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                
                implementation(libs.google.code.gson)
            }
        }
        
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.vlcj)
                implementation(libs.jaudiotagger)
                implementation(libs.onnxruntime.jvm)
                // MPRIS lives on the session bus; the native-unixsocket transport uses the
                // JDK's own unix domain sockets, so it needs no JNI and no extra native library.
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.unixsocket)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "music.ai.recommend.MainKt"

        // The MPRIS DesktopEntry property has to name the .desktop file this build actually
        // installs, and only the build knows it: jpackage joins the package name and the app name
        // whenever they differ, so "aimusic" + "AiMusic" ships as aimusic-AiMusic.desktop.
        // Passing it in keeps the packaging detail out of the application code, which falls back
        // to a plain name when run straight from Gradle.
        jvmArgs("-Daimusic.desktopEntry=aimusic-AiMusic")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AiMusic"
            packageVersion = "1.0.2"
            description = "Music player with CLAP audio search and smart albums"
            vendor = "Nikita"

            // The first four are what `suggestRuntimeModules` derives from the bytecode.
            // jdk.charsets is not among them and has to be added by hand: the library scanner
            // repairs mis-tagged ID3 text with Charset.forName("Windows-1251"), a lookup by name
            // that no static analysis can see. Leaving it out costs nothing under `run`, which
            // uses the full JDK, and turns every Cyrillic tag into mojibake once installed.
            modules("java.instrument", "java.sql", "jdk.security.auth", "jdk.unsupported", "jdk.charsets")


            linux {
                // Debian package names are lowercase; this is also what the installed tree under
                // /opt and the .deb file itself are named after.
                packageName = "aimusic"
                debMaintainer = "sharap.software@gmail.com"
                appCategory = "Audio"
                menuGroup = "Audio"
                iconFile.set(project.file("packaging/aimusic.png"))
                shortcut = true
            }
        }
    }
}

/**
 * Adds the Debian dependencies the package needs at runtime.
 *
 * libvlc is dlopen-ed through JNA rather than linked, and ffmpeg is spawned per track by the AI
 * scan, so neither appears in any dependency scan: without declaring them the package installs
 * cleanly and then silently plays nothing. jpackage can declare them (`--linux-package-deps`) but
 * the Compose DSL exposes no way to pass the flag, so the control file is rewritten in place once
 * jpackage is done.
 *
 * In afterEvaluate because the Compose plugin registers its packaging tasks after this script is
 * evaluated. Everything the task action needs is resolved here, at configuration time, and only
 * plain values are captured — a reference to a script-level declaration would make the task
 * unserialisable for the configuration cache.
 */
afterEvaluate {
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val workDir = layout.buildDirectory.dir("tmp/debRuntimeDeps")
    val runtimeDependencies = listOf("libvlc5", "vlc-plugin-base", "ffmpeg")

    tasks.named("packageDeb") {
        doLast {
            val debFile = debDir.get().asFile
                .listFiles { f -> f.extension == "deb" }
                ?.maxByOrNull { it.lastModified() }
                ?: error("packageDeb produced no .deb to add dependencies to")

            val work = workDir.get().asFile
            work.deleteRecursively()

            fun run(vararg command: String) {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
            }

            // fakeroot, so the ownership inside the archive survives the unpack and repack.
            run("fakeroot", "dpkg-deb", "-R", debFile.absolutePath, work.absolutePath)

            val control = File(work, "DEBIAN/control")
            val lines = control.readLines().toMutableList()
            val existing = lines.indexOfFirst { it.startsWith("Depends:") }
            val declared = runtimeDependencies.joinToString(", ")
            if (existing >= 0) {
                val current = lines[existing].removePrefix("Depends:").trim()
                val merged = (current.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
                    runtimeDependencies).distinct()
                lines[existing] = "Depends: " + merged.joinToString(", ")
            } else {
                // Right after Architecture, where dpkg conventionally puts it.
                val at = lines.indexOfFirst { it.startsWith("Architecture:") }
                lines.add(if (at >= 0) at + 1 else lines.size, "Depends: $declared")
            }
            control.writeText(lines.joinToString("\n", postfix = "\n"))

            run("fakeroot", "dpkg-deb", "-b", work.absolutePath, debFile.absolutePath)
            work.deleteRecursively()
            println("packageDeb: declared Depends: $declared")
        }
    }
}
