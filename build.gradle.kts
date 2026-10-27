import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
}

sourceSets {
    create("uiTest")
}

val ideStarterVersion = "262.9437.185"

dependencies {
    // The domain core: model, lifecycle, anchoring policy, persistence codec.
    implementation(project(":core"))
    kover(project(":core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")

    "uiTestImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "uiTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.4")
    "uiTestImplementation"("org.kodein.di:kodein-di-jvm:7.26.1")
    "uiTestImplementation"("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    "uiTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    "uiTestImplementation"("org.jetbrains.teamcity:serviceMessages:2024.07")
    // IDE Starter drives a separately installed IDE, so it is pinned apart
    // from the compile platform; on a 2025.2 platform the product modules
    // aren't added for us.
    "uiTestImplementation"("com.jetbrains.intellij.tools:ide-starter-product-idea-ultimate:$ideStarterVersion")

    intellijPlatform {
        // Markdown parsing for message bodies comes from the IDE's bundled
        // Markdown plugin (org.intellij.markdown lives inside it) — declared
        // as a real plugin dependency instead of bundling our own copy of an
        // IDE package, per the Marketplace verifier.
        bundledPlugin("org.intellij.plugins.markdown")
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
        // CI runs this against JetBrains' recommended IDE set for our
        // since-build range — the proof behind the declared 2025.2+ support.
        // Fail on real problems; deprecated/experimental/internal *usages*
        // are reported but advisory (and NOT_DYNAMIC is a known, accepted
        // limitation for now).
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
        }
    }
}

tasks.register<Test>("uiTest") {
    description = "Drives a real IDE with the built plugin installed."
    group = "verification"
    val uiTestSourceSet = sourceSets["uiTest"]
    testClassesDirs = uiTestSourceSet.output.classesDirs
    classpath = uiTestSourceSet.runtimeClasspath
    useJUnitPlatform { includeEngines("junit-jupiter") }
    val plugin = tasks.buildPlugin.flatMap { it.archiveFile }
    dependsOn(tasks.buildPlugin)
    systemProperty("marginalis.plugin", plugin.get().asFile.absolutePath)
    val installedIde = providers.gradleProperty("marginalis.localIde").orNull
    onlyIf("marginalis.localIde names an installed IDE to drive") { installedIde != null }
    installedIde?.let { systemProperty("marginalis.uiTestIde", it) }
}

tasks.test {
    systemProperty("marginalis.rootProjectName", rootProject.name)
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
