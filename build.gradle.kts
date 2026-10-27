import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

plugins {
    // Kotlin 2.3.x: first line with official Gradle 9 support (we run Gradle 9.1
    // because the shared JDK is Java 25, which needs Gradle 9.1+).
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    // CHANGELOG.md -> Marketplace change notes (see pluginConfiguration).
    id("org.jetbrains.changelog") version "2.2.1"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "dev.marginalis"
version = "0.2.7"

// Stamp the build version into a resource ping can serve: the platform's
// plugin-manager lookups (PluginManagerCore.getPlugin AND
// PluginManager.findEnabledPlugin) are both internal API per the verifier.
tasks.processResources {
    val pluginVersion = version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("marginalis/plugin-version.properties") {
        expand("version" to pluginVersion)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    // Remote-Robot's client, for the sandbox UI tests only.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
        content { includeGroup("com.intellij.remoterobot") }
    }
}

sourceSets {
    create("uiTest")
}

val ideStarterVersion = "262.9437.185"
// The latest released IntelliJ IDEA (gradle.properties): the Plugin Verifier's second IDE and the sandbox image's.
val latestIde: String = providers.gradleProperty("marginalis.latestIde").get()
// The same version as the robot-server plugin sandbox/init.gradle installs into the sandbox IDE.
val remoteRobotVersion = "0.11.23"

dependencies {
    // The domain core: model, lifecycle, anchoring policy, persistence codec.
    implementation(project(":core"))
    kover(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    // Without its Kotlin and coroutines: the IDE under test brings its own, and the older copies Kotest names would
    // shadow them on the test classpath (NoSuchMethodError in the plugin reload tests).
    testImplementation("io.kotest:kotest-assertions-core:5.9.1") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }

    "uiTestImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "uiTestImplementation"("io.kotest:kotest-assertions-core:5.9.1")
    // Compiled against: the CI shard filter is a launcher extension.
    "uiTestImplementation"("org.junit.platform:junit-platform-launcher:1.13.4")
    "uiTestImplementation"("org.kodein.di:kodein-di-jvm:7.26.1")
    "uiTestImplementation"("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    "uiTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    "uiTestImplementation"("org.jetbrains.teamcity:serviceMessages:2024.07")
    // IDE Starter drives a separately installed IDE, so it is pinned apart
    // from the compile platform; on a 2025.2 platform the product modules
    // aren't added for us.
    "uiTestImplementation"("com.jetbrains.intellij.tools:ide-starter-product-idea-ultimate:$ideStarterVersion")
    "uiTestImplementation"("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    // Remote-Robot's own HTTP client, given a read timeout long enough for a forced settings save.
    "uiTestImplementation"("com.squareup.okhttp3:okhttp:3.14.9")

    intellijPlatform {
        // Markdown parsing for message bodies comes from the IDE's bundled
        // Markdown plugin (org.intellij.markdown lives inside it) — declared
        // as a real plugin dependency instead of bundling our own copy of an
        // IDE package, per the Marketplace verifier.
        bundledPlugin("org.intellij.plugins.markdown")
        // The review diff's file list (ReviewDiffFileList.java): compiled against, so the Plugin
        // Verifier checks those calls on every IDE; the IDE provides both modules at runtime.
        bundledModule("intellij.platform.collaborationTools")
        bundledModule("intellij.platform.vcs.impl")
        // Default: compile against the oldest supported target (the honest
        // floor; CI uses this). Environments that can't reach the JetBrains
        // CDN — like the tenant sandbox — set `marginalis.localIde` in their
        // machine-local ~/.gradle/gradle.properties to point at an installed
        // IDE instead (see CLAUDE.md).
        val localIde = providers.gradleProperty("marginalis.localIde").orNull
        if (localIde != null) {
            local(localIde)
        } else {
            intellijIdeaCommunity("2025.2")
        }
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, version = ideStarterVersion, configurationName = "uiTestImplementation")
        // Pinned: undeclared, each is "latest", looked up in JetBrains' repository on every run, so a hiccup there failed
        // `verify` and the verdict depended on the day. A newer one is a deliberate bump (docs/upgrades.md).
        pluginVerifier("1.411")
        zipSigner("0.1.43")
    }
}

intellijPlatform {
    // We do have a settings page now, but this forks a headless IDE the
    // sandbox can't run; the one page is findable by name regardless.
    buildSearchableOptions = false

    // Needs java-compiler-ant-tasks from the blocked CDN, and we have no GUI
    // forms or @NotNull Java bytecode to instrument anyway.
    instrumentCode = false

    pluginConfiguration {
        id = "dev.marginalis.plugin"
        name = "Marginalis"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "252"
            // Omit until-build entirely: the host IDE that installs the zip may
            // be any version ≥ 2025.2, and JetBrains discourages upper bounds.
            untilBuild = provider { null }
        }
        // The Marketplace "What's New" tab: the CHANGELOG.md section matching
        // this build's version, rendered to HTML — release notes live in one
        // reviewable file, never inline in the build. Falls back to
        // [Unreleased] so snapshot builds show work-in-progress notes.
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Marketplace publishing: all four values arrive as CI secrets (see
    // release.yml); locally they're simply absent and the tasks are skipped.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Stable since 0.1.25 (#7). PUBLISH_CHANNEL still overrides for a
        // one-off EAP or beta run.
        channels = providers.environmentVariable("PUBLISH_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }

    pluginVerification {
        // CI checks both ends of the declared 2025.2+ support: the floor, as JetBrains' recommended IDE set for our
        // since-build range resolves it, and the latest released IntelliJ IDEA, the one the sandbox scenarios run.
        // Fail on real problems; deprecated/experimental *usages* are reported but advisory (and NOT_DYNAMIC is a
        // known, accepted limitation for now).
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            // Promoted from advisory after the Marketplace flagged what CI had
            // only whispered: internal and scheduled-for-removal usages now
            // fail the build. DEPRECATED stays advisory — the markdown lib's
            // deprecations vary by IDE version (accepted, see BACKLOG).
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
        )
        ides {
            recommended()
            // The unified distribution: a headless verification needs no licence.
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, latestIde)
        }
    }
}

tasks.test {
    // Bylines format the clock with the default locale; pin it so AM/PM reads
    // the same on every machine, as it does on CI.
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}

tasks.register<Test>("uiTest") {
    description = "Drives a real IDE with the built plugin installed."
    group = "verification"
    val uiTestSourceSet = sourceSets["uiTest"]
    testClassesDirs = uiTestSourceSet.output.classesDirs
    classpath = uiTestSourceSet.runtimeClasspath
    useJUnitPlatform { includeEngines("junit-jupiter") }
    filter { excludeTestsMatching("dev.marginalis.plugin.sandbox.*") }
    val plugin = tasks.buildPlugin.flatMap { it.archiveFile }
    dependsOn(tasks.buildPlugin)
    systemProperty("marginalis.plugin", plugin.get().asFile.absolutePath)
    val installedIde = providers.gradleProperty("marginalis.localIde").orNull
    onlyIf("marginalis.localIde names an installed IDE to drive") { installedIde != null }
    installedIde?.let { systemProperty("marginalis.uiTestIde", it) }
}

// The sandbox scenarios' IDE runs in Docker (sandbox/Dockerfile) unless MARGINALIS_SANDBOX_LAUNCH says otherwise.
val sandboxInDocker = providers.environmentVariable("MARGINALIS_SANDBOX_LAUNCH").orElse("docker").get() == "docker"
val sandboxPrebuiltDir = layout.buildDirectory.dir("sandbox-prebuilt")
// A fixed robot port fits one sandbox only.
val sandboxJobs = if (System.getenv("MARGINALIS_ROBOT_PORT") != null) 1
    else providers.gradleProperty("marginalis.sandbox.jobs").orElse("6").get().toInt()

// One sandbox run at a time on the machine, `sandbox/sandbox.py locked` experiments included: two runs side by side
// saturate the CPU and their scenarios time out. The lock holds the owner's pid and start time (the format
// sandbox/sandbox.py writes too). It is stale once that pid is gone, or, when the owner cannot be read, after 2 h.
val sandboxRunLock: java.nio.file.Path = layout.projectDirectory.asFile.toPath().parent.resolve("marginalis-sandbox-runs").resolve(".lock")
val sandboxRunLockOwner = ProcessHandle.current().pid()

fun sandboxRunLockText(): String? = runCatching { Files.readString(sandboxRunLock) }.getOrNull()

fun sandboxRunLockIsStale(lockText: String): Boolean {
    val owner = lockText.lineSequence().firstOrNull { it.startsWith("pid=") }?.removePrefix("pid=")?.trim()?.toLongOrNull()
    val age = runCatching { Duration.between(Files.getLastModifiedTime(sandboxRunLock).toInstant(), Instant.now()) }.getOrNull() ?: return false
    val alive = owner?.let { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } ?: false
    return !alive && (owner != null || age > Duration.ofHours(2))
}

// Another waiter may have replaced the judged lock with its own meanwhile; that one stays.
fun deleteSandboxRunLockIfStill(judgedText: String) {
    if (sandboxRunLockText() == judgedText) Files.deleteIfExists(sandboxRunLock)
}

fun releaseSandboxRunLock() {
    val ours = runCatching { Files.readString(sandboxRunLock).contains("pid=$sandboxRunLockOwner\n") }.getOrDefault(false)
    if (ours) Files.deleteIfExists(sandboxRunLock)
}

fun dockerLines(vararg args: String): List<String> {
    val process = ProcessBuilder(listOf("docker") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val lines = process.inputStream.bufferedReader().readLines().filter(String::isNotBlank)
    return if (process.waitFor() == 0) lines else emptyList()
}

fun dockerSucceeds(vararg args: String): Boolean =
    ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0

// The pool's `-work` volumes and the manual sandbox's pair (sandbox.py, port 8082) are reused run after run; any other
// volume of ours (an older harness's names, run mode's Gradle caches) no run claims again.
val reusedSandboxVolume = Regex("marginalis-sandbox-(81\\d\\d-work|8082-work|8082-gradle)")

// Another checkout tags the image by its own Dockerfile and IDE: the two newest other tags spare it a rebuild.
val otherSandboxImageTagsKept = 2

fun sweepSandboxLeftovers() {
    val volumes = dockerLines("volume", "ls", "--format", "{{.Name}}").filter { it.startsWith("marginalis-") && !reusedSandboxVolume.matches(it) }
    val images = dockerLines("images", "marginalis-sandbox", "--format", "{{.Tag}}")
        .filter { it != "<none>" && it != sandboxImageTag.substringAfter(':') }
        .drop(otherSandboxImageTagsKept)
        .map { "marginalis-sandbox:$it" }
    // Docker refuses what a container still uses; that stays for a later run.
    val removedVolumes = volumes.filter { dockerSucceeds("volume", "rm", it) }
    val removedImages = images.filter { dockerSucceeds("rmi", it) }
    if (removedVolumes.isNotEmpty() || removedImages.isNotEmpty()) {
        logger.lifecycle("removed what no sandbox reuses: volumes ${removedVolumes.ifEmpty { "none" }}, images ${removedImages.ifEmpty { "none" }}")
    }
}

val sandboxRunUnlock = tasks.register("sandboxRunUnlock") {
    description = "Releases the machine-wide sandbox run lock this build holds."
    group = "verification"
    doLast { releaseSandboxRunLock() }
}

val sandboxRunLocked = tasks.register("sandboxRunLocked") {
    description = "Waits for the machine-wide sandbox run lock and takes it."
    group = "verification"
    // A single sandbox outside Docker is one IDE on this machine, like any other.
    onlyIf("the run uses Docker or runs several sandboxes") { sandboxInDocker || sandboxJobs > 1 }
    finalizedBy(sandboxRunUnlock)
    doLast {
        Files.createDirectories(sandboxRunLock.parent)
        while (true) {
            try {
                Files.writeString(sandboxRunLock, "pid=$sandboxRunLockOwner\nstarted=${Instant.now()}\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                break
            } catch (taken: FileAlreadyExistsException) {
                val judgedText = sandboxRunLockText() ?: continue
                // A daemon runs one build at a time, so a lock under its own pid is a cancelled earlier build's, whose
                // unlock finalizer never ran.
                if (judgedText.lineSequence().any { it == "pid=$sandboxRunLockOwner" }) {
                    logger.lifecycle("taking over the sandbox run lock $sandboxRunLock left by an earlier build of this Gradle daemon (pid $sandboxRunLockOwner)")
                    deleteSandboxRunLockIfStill(judgedText)
                    continue
                }
                if (sandboxRunLockIsStale(judgedText)) {
                    logger.lifecycle("taking the stale sandbox run lock $sandboxRunLock")
                    deleteSandboxRunLockIfStill(judgedText)
                    continue
                }
                val holder = runCatching { Files.readString(sandboxRunLock).trim().replace('\n', ' ') }.getOrDefault("?")
                logger.lifecycle("waiting for the sandbox run lock $sandboxRunLock ($holder)")
                Thread.sleep(15_000)
            }
        }
        // A build stopped halfway lets go of the lock too.
        Runtime.getRuntime().addShutdownHook(Thread { releaseSandboxRunLock() })
        // Under the lock no other Kotlin run is alive, so a container on a Kotlin sandbox port is an orphan: on Windows
        // a cancelled run's test JVM is terminated without its shutdown hook.
        if (sandboxInDocker) {
            val listing = ProcessBuilder("docker", "ps", "-a", "--format", "{{.Names}}").redirectErrorStream(true).start()
            val orphans = listing.inputStream.bufferedReader().readLines().filter { Regex("marginalis-sandbox-81\\d\\d").matches(it) }
            listing.waitFor()
            if (orphans.isNotEmpty()) {
                logger.lifecycle("removing sandbox containers an earlier run left behind: ${orphans.joinToString()}")
                ProcessBuilder(listOf("docker", "rm", "-f") + orphans).redirectErrorStream(true).start().waitFor()
            }
            sweepSandboxLeftovers()
        }
    }
}

tasks.test {
    systemProperty("marginalis.rootProjectName", rootProject.name)
    // settings.gradle.kts drops an IDE index without Java; one built again without it would fail the code-fence tests
    // far from the cause.
    val ideIndexes = layout.projectDirectory.dir(".intellijPlatform/layoutIndex")
    doFirst {
        val withoutJava = ideIndexes.asFile.listFiles { file -> file.extension == "json" }.orEmpty()
            .filter { index -> "\"id\":\"com.intellij.java\"" !in index.readText() }
        check(withoutJava.isEmpty()) {
            "the IDE index ${withoutJava.joinToString { it.name }} lacks the Java plugin " +
                "(intellij-platform-gradle-plugin#2192): run the build again, it indexes the IDE anew"
        }
    }
    // Every test's agent calls come from this one JVM, which the server's per-client limit counts as a single agent.
    systemProperty("marginalis.api.requestsPerMinute", Int.MAX_VALUE)
    // The short format names only the failing line; the message (an HTTP status, a measured value) says why.
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

allprojects {
    val guarded = this
    plugins.withType<JavaBasePlugin> {
        guarded.extensions.getByType<SourceSetContainer>().configureEach {
            val kind = if (name == SourceSet.MAIN_SOURCE_SET_NAME) "production" else "test"
            val property = "marginalis.ownClasses.$kind.$name.${guarded.path}"
            val classesDirs = output.classesDirs
            rootProject.tasks.named<Test>("test") {
                inputs.files(classesDirs).withPropertyName(property)
                jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-D$property=${classesDirs.asPath}") })
            }
        }
    }
}

// In the order sandbox/sandbox.py hashes them too, so both harnesses build one image under one tag.
val sandboxImageBuildArgs: List<String> = listOf(
    "SANDBOX_IDE=$latestIde",
    "SANDBOX_IDE_SHA256=${providers.gradleProperty("marginalis.latestIde.sha256").get()}",
)

// The image is tagged by what it is built from: sandboxes of another checkout (or the Python harness on another
// branch) building a different entrypoint or IDE under one shared tag would swap the image under running scenarios.
val sandboxImageTag: String = run {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf("Dockerfile", "docker-entrypoint.sh").forEach { name ->
        digest.update(layout.projectDirectory.file("sandbox/$name").asFile.readText().replace("\r\n", "\n").toByteArray())
    }
    sandboxImageBuildArgs.forEach { buildArg -> digest.update("$buildArg\n".toByteArray()) }
    "marginalis-sandbox:" + digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

val sandboxImage = tasks.register<Exec>("sandboxImage") {
    description = "Builds the sandbox IDE's Docker image (a no-op once Docker has it cached)."
    group = "verification"
    commandLine(
        listOf("docker", "build", "-q") +
            sandboxImageBuildArgs.flatMap { buildArg -> listOf("--build-arg", buildArg) } +
            listOf("-t", sandboxImageTag, layout.projectDirectory.dir("sandbox").asFile.absolutePath),
    )
}

apply(from = "sandbox/ui-ide.gradle")

// `-Pmarginalis.pluginZip=<zip>` hands the sandboxes and the Plugin Verifier a plugin built elsewhere (CI's package
// job), and this build then compiles nothing of the plugin; by default they get this checkout's own distribution.
val pluginZip: Any = providers.gradleProperty("marginalis.pluginZip").orNull?.let { file(it) }
    ?: tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }

val sandboxRobotServer = configurations.create("sandboxRobotServer") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    sandboxRobotServer("com.intellij.remoterobot:robot-server-plugin:$remoteRobotVersion@zip")
}

val sandboxPrebuilt = tasks.register<Sync>("sandboxPrebuilt") {
    description = "Gathers the plugins the sandboxes start with: the distribution zip, plus Remote Robot's server."
    group = "verification"
    from(sandboxRobotServer.elements.map { zips -> zips.map { zipTree(it) } })
    from(zipTree(pluginZip))
    into(sandboxPrebuiltDir.map { it.dir("plugins") })
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>("verifyPlugin") {
    archiveFile.set(layout.file(provider { pluginZip as? File }).orElse(tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }))
}

tasks.register<Test>("uiSandboxTest") {
    description = "Runs the sandbox scenarios against sandbox IDEs built from this checkout (see sandbox/README.md)."
    group = "verification"
    dependsOn(sandboxRunLocked)
    if (sandboxInDocker) {
        dependsOn(sandboxImage, sandboxPrebuilt)
        systemProperty("marginalis.sandbox.prebuilt", sandboxPrebuiltDir.get().asFile.absolutePath)
        systemProperty("marginalis.sandbox.image", sandboxImageTag)
    }
    // One JVM runs the scenario classes side by side, each with a sandbox of its own, the longest first (Gradle's
    // forks would take them in scan order).
    val jobs = sandboxJobs
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", jobs.toString())
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", jobs.toString())
    systemProperty("junit.jupiter.testclass.order.default", "dev.marginalis.plugin.sandbox.LongestFirst")
    val uiTestSourceSet = sourceSets["uiTest"]
    testClassesDirs = uiTestSourceSet.output.classesDirs
    classpath = uiTestSourceSet.runtimeClasspath
    // `-Pmarginalis.sandbox.tags=core` runs the critical flows alone; the value is a JUnit tag expression.
    val tags = providers.gradleProperty("marginalis.sandbox.tags").orNull
    useJUnitPlatform {
        includeEngines("junit-jupiter")
        tags?.let { includeTags(it) }
    }
    filter { includeTestsMatching("dev.marginalis.plugin.sandbox.*") }
    // `-Pmarginalis.sandbox.shard=1/2` runs the scenarios sandbox/shards.json gives shard 1, for CI's parallel e2e
    // shards; the harness's own tests go with shard 1.
    providers.gradleProperty("marginalis.sandbox.shard").orNull?.let { shard -> systemProperty("marginalis.sandbox.shard", shard) }
    // A scenario's outcome depends on the IDE it drives, not only on these inputs.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // Remote-Robot's client reads an IDE-side error into a Throwable through Gson's reflection.
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    systemProperty("marginalis.sandbox.featureDir", layout.projectDirectory.asFile.absolutePath)
}

tasks.named<org.gradle.api.tasks.JavaExec>("runIde") {
    // Open the toy project so the M0 loop can be exercised immediately.
    args(layout.projectDirectory.dir("sample-project").asFile.absolutePath)
}

// IPGP auto-configures a Java *21* toolchain for the 2025.2 target, but the
// sandbox can't provision one (toolchain downloads are network-blocked). Pin
// the toolchain to the JDK share we actually have (25) and emit 21 bytecode
// via explicit source/target compatibility + jvmTarget.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kover {
    currentProject {
        instrumentation {
            // Kover runs every Test task for its reports, and `check` verifies them: without this, `build` would
            // start IDEs (the sandbox suite builds a Docker image and takes the machine lock). They are run by name.
            disabledForTestTasks.addAll("uiTest", "uiSandboxTest")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Without this, implementing a Kotlin interface generates bridge
        // overrides for every default method — the Plugin Verifier then
        // reports us "overriding" deprecated/internal APIs we never wrote.
        // (-jvm-default=no-compatibility is the stable spelling of the old
        // -Xjvm-default=all.)
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = JvmTarget.JVM_21
    // The plugin runs on the IDE's own Kotlin stdlib, on the floor (2025.2) a 2.2 one: code built for 2.3 crashed every
    // resume of a plugin coroutine there (debug metadata 2 vs 1), and newer stdlib calls would fail at runtime.
    compilerOptions.apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
    compilerOptions.languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
}

pitest {
    pitestVersion = "1.30.1"
    targetClasses = setOf("dev.marginalis.plugin.ui.WalkthroughNavigator*")
    targetTests = setOf("dev.marginalis.plugin.ui.WalkthroughNavigatorTest")
    threads = 2
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    avoidCallsTo = setOf("kotlin.jvm.internal")
    useClasspathFile = true
}

tasks.named<info.solidsoft.gradle.pitest.PitestTask>("pitest") {
    dependsOn("prepareTestSandbox")
    val test = tasks.test.get()
    additionalClasspath.from(test.classpath)
    childProcessJvmArgs = provider { test.allJvmArgs }
}

sandboxRunUnlock.configure { mustRunAfter(sandboxPrebuilt, "uiSandboxTest") }
