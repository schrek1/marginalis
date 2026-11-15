package dev.marginalis.plugin.testing

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import java.io.File

object ExperimentalApiSeam {

    private const val PRODUCTION_CLASSES = "marginalis.ownClasses.production.main.:"
    private const val SEAM = "dev.marginalis.plugin.ui.diff.ReviewDiffFileList"
    private const val SEAM_CALLER = "dev.marginalis.plugin.ui.diff.ReviewDiffSteps"
    private const val EXPERIMENTAL_PACKAGE = "com.intellij.collaboration"

    fun check() {
        val production = checkNotNull(System.getProperty(PRODUCTION_CLASSES)) { "the test task must set -D$PRODUCTION_CLASSES" }
        val dirs = production.split(File.pathSeparator).map(::File).filter(File::isDirectory).map(File::toPath)
        val imported = ClassFileImporter().importPaths(dirs)
        listOf(SEAM, SEAM_CALLER).forEach { name ->
            check(imported.contain(name)) { "$name is not among the production classes in $dirs" }
        }
        check(imported)
    }

    private fun check(production: JavaClasses) {
        classes().should(reachExperimentalApiOnlyThroughTheSeam())
            .because("experimental platform API stays behind one Java class, which only a lazily run lambda may touch")
            .check(production)
    }

    private fun reachExperimentalApiOnlyThroughTheSeam() = object : ArchCondition<JavaClass>("reach experimental API only through $SEAM") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            if (item.name == SEAM) return
            for (dependency in item.directDependenciesFromSelf) {
                val target = dependency.targetClass.baseComponentType
                val origin = dependency.description.substringBefore(">")
                val violation = when {
                    target.packageName == EXPERIMENTAL_PACKAGE || target.packageName.startsWith("$EXPERIMENTAL_PACKAGE.") ->
                        "uses experimental API directly"
                    target.name == SEAM && !(item.name == SEAM_CALLER && "\$lambda\$" in origin) ->
                        "touches $SEAM outside $SEAM_CALLER's lazily run lambda"
                    else -> null
                }
                if (violation != null) events.add(SimpleConditionEvent.violated(item, "${dependency.description}: $violation"))
            }
        }
    }
}
