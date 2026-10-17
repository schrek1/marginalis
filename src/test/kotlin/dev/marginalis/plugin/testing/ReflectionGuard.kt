package dev.marginalis.plugin.testing

import com.tngtech.archunit.base.HasDescription
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.SourceCodeLocation
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import java.io.File

object ReflectionGuard {

    private const val OWN_CLASSES = "marginalis.ownClasses."
    private const val ROOT_PROJECT_NAME = "marginalis.rootProjectName"
    private const val ROOT_PROJECT_PATH = ":"
    private const val HOW_TO_FIX = "reflection reaches code by name, so the Plugin Verifier can't see it. " +
        "Use public API; in plugin tests only, mark the one function @AllowReflection(\"why\")"

    private val FORBIDDEN_PACKAGES = listOf(
        "java.lang.reflect", "java.lang.invoke", "kotlin.reflect.full", "kotlin.reflect.jvm", "sun.reflect", "jdk.internal.reflect",
    )

    private val FORBIDDEN_CLASSES = setOf(
        "java.beans.Expression", "java.beans.Statement", "java.beans.EventHandler",
        "com.intellij.util.ReflectionUtil", "com.intellij.util.ReflectionUtilRt", "com.intellij.util.MethodInvocator",
    )

    private val CLASS_TYPE_IDENTITY_AND_RESOURCES = setOf(
        "getName", "getSimpleName", "getClassLoader", "isInstance", "cast", "getResource", "getResourceAsStream",
    )

    private val CLASS_LOADER_RESOURCES = setOf("getResource", "getResourceAsStream", "getResources")

    private val KOTLIN_COMPILER_GENERATED = setOf(
        "getOrCreateKotlinClass", "getOrCreateKotlinClasses", "getOrCreateKotlinPackage",
        "typeOf", "nullableTypeOf", "platformType", "mutableCollectionType",
        "function", "property0", "property1", "property2", "mutableProperty0", "mutableProperty1", "mutableProperty2",
    )

    private val KOTLIN_COMPILER_GENERATED_PROJECTIONS = setOf("invariant", "covariant", "contravariant", "getSTAR")

    private val KOTLIN_TYPE_IDENTITY_AND_REFERENCES = setOf("getSimpleName", "getQualifiedName", "getName", "isInstance", "get", "set", "invoke")

    private class SourceSetClasses(
        val projectPath: String,
        val project: String,
        val sourceSet: String,
        val isProduction: Boolean,
        val compiledTo: List<File>,
    ) {
        val dirs = compiledTo.filter(File::isDirectory)

        val mayOptOut = projectPath == ROOT_PROJECT_PATH && !isProduction

        fun contains(javaClass: JavaClass): Boolean {
            val path = javaClass.source.map { File(it.uri).path }.orElse("")
            return dirs.any { path.startsWith(it.path + File.separator) }
        }
    }

    fun check() {
        val rootProjectName = checkNotNull(System.getProperty(ROOT_PROJECT_NAME)) { "the test task must set -D$ROOT_PROJECT_NAME" }
        val sourceSets = discoveredSourceSets(rootProjectName)
        check(sourceSets.isNotEmpty()) { "the test task passed no -D$OWN_CLASSES* class directories" }
        val classes = ClassFileImporter().importPaths(sourceSets.flatMap { it.dirs }.distinct().map { it.toPath() })
        val owners = classes.associateWith { javaClass ->
            sourceSets.firstOrNull { it.contains(javaClass) } ?: error("${javaClass.name} came from no -D$OWN_CLASSES* directory")
        }
        sourceSets.filter { it.isProduction }.groupBy { it.projectPath }.forEach { (projectPath, production) ->
            check(owners.values.any { it.projectPath == projectPath && it.isProduction }) {
                "[${production.first().project}] contributed no production classes: nothing compiled to ${production.flatMap { it.compiledTo }}"
            }
        }
        rule(owners).check(classes)
    }

    private fun discoveredSourceSets(rootProjectName: String): List<SourceSetClasses> =
        System.getProperties().stringPropertyNames().filter { it.startsWith(OWN_CLASSES) }.sorted().map { property ->
            val (kind, sourceSet, projectPath) = property.removePrefix(OWN_CLASSES).split('.', limit = 3)
            val compiledTo = System.getProperty(property).split(File.pathSeparator).filter(String::isNotEmpty).map { File(it).absoluteFile }
            val project = projectPath.removePrefix(":").ifEmpty { rootProjectName }
            SourceSetClasses(projectPath, project, sourceSet, kind == "production", compiledTo)
        }

    private fun rule(owners: Map<JavaClass, SourceSetClasses>): ArchRule =
        classes().should(useNoReflection(owners)).because(HOW_TO_FIX)

    private fun useNoReflection(owners: Map<JavaClass, SourceSetClasses>) =
        object : ArchCondition<JavaClass>("use no reflection") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val owner = owners.getValue(item)
                val origins = item.codeUnits.toList<HasDescription>() + item.fields + item
                val dependencies = item.directDependenciesFromSelf
                    .filter { isForbidden(it.targetClass.baseComponentType) }
                    .map { Violation(it.description, it.sourceCodeLocation) }
                val calls = item.codeUnits
                    .flatMap { it.callsFromSelf + it.codeUnitReferencesFromSelf }
                    .filter(::isForbiddenMember)
                    .map { Violation(it.description, it.sourceCodeLocation) }
                (dependencies + calls)
                    .map { violation -> violation to (originOf(violation, origins) ?: item) }
                    .filterNot { (_, origin) -> owner.mayOptOut && origin is JavaCodeUnit && isExempt(origin) }
                    .forEach { (violation, origin) ->
                        events.add(SimpleConditionEvent.violated(item, "[${owner.project}] " + violation.describeFrom(origin)))
                    }
            }
        }

    private fun originOf(violation: Violation, origins: List<HasDescription>): HasDescription? =
        origins.filter { violation.description.startsWith(it.description + " ") }.maxByOrNull { it.description.length }

    private class Violation(val description: String, val location: SourceCodeLocation) {
        fun describeFrom(origin: HasDescription): String {
            val touches = description.removePrefix(origin.description).substringBeforeLast(" in (").trim()
            val name = when (origin) {
                is JavaMember -> "${origin.owner.name.substringAfterLast('.')}.${origin.name}"
                is JavaClass -> origin.name.substringAfterLast('.')
                else -> origin.description
            }
            val line = location.lineNumber.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            return "$name(${location.sourceFileName}$line) → $touches"
        }
    }

    private fun isForbidden(target: JavaClass): Boolean =
        FORBIDDEN_PACKAGES.any { target.packageName == it || target.packageName.startsWith("$it.") } || target.name in FORBIDDEN_CLASSES

    private fun isForbiddenMember(access: JavaAccess<*>): Boolean {
        val owner = access.targetOwner
        val name = access.name
        return when {
            owner.name == "java.lang.Class" -> name !in CLASS_TYPE_IDENTITY_AND_RESOURCES
            owner.simpleName.endsWith("ClassLoader") -> name !in CLASS_LOADER_RESOURCES
            owner.name == "kotlin.jvm.internal.Reflection" -> name !in KOTLIN_COMPILER_GENERATED
            owner.name == "kotlin.reflect.KTypeProjection\$Companion" -> name !in KOTLIN_COMPILER_GENERATED_PROJECTIONS
            owner.packageName == "kotlin.reflect" -> name !in KOTLIN_TYPE_IDENTITY_AND_REFERENCES
            else -> false
        }
    }

    private fun isExempt(codeUnit: JavaCodeUnit, seen: MutableSet<JavaCodeUnit> = mutableSetOf()): Boolean {
        if (!seen.add(codeUnit)) return false
        return codeUnit.isAnnotatedWith(AllowReflection::class.java.name) ||
            codeUnit.owner.enclosingCodeUnit.map { isExempt(it, seen) }.orElse(false) ||
            (codeUnit.isLocalFunction() && codeUnit.accessesToSelf.any { it.originOwner == codeUnit.owner && isExempt(it.origin, seen) })
    }

    private fun JavaCodeUnit.isLocalFunction(): Boolean = '$' in name && JavaModifier.PRIVATE in modifiers
}
