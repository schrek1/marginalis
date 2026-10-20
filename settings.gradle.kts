rootProject.name = "marginalis"
include(":core")

// The IntelliJ Platform Gradle plugin sometimes leaves the bundled Java plugin out of its index of the IDE
// (JetBrains/intellij-platform-gradle-plugin#2192, fixed after 2.19.0), and the index stays: every later build then
// compiles and tests without Java. Dropped here, before the plugin reads it, the index is built again. CI checks the
// same (.github/actions/ide-layout-index); `test` refuses an index that still lacks it (build.gradle.kts).
rootDir.resolve(".intellijPlatform/layoutIndex").listFiles { file -> file.extension == "json" }.orEmpty()
    .filter { index -> "\"id\":\"com.intellij.java\"" !in index.readText() }
    .forEach { index ->
        println("dropping ${index.name}: this index of the IDE lacks its Java plugin, so the build indexes the IDE again")
        index.delete()
    }
