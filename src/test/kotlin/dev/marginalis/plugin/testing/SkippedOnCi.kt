package dev.marginalis.plugin.testing

import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.RunNotifier

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OffCiOnly

class SkippedOnCi(testClass: Class<*>) : JUnit38ClassRunner(testClass) {

    override fun run(notifier: RunNotifier) {
        if (System.getenv("CI") != "true") return super.run(notifier)
        val offCi = leaves(description).filter { it.getAnnotation(OffCiOnly::class.java) != null }
        offCi.forEach(notifier::fireTestIgnored)
        if (offCi.size == leaves(description).size) return
        filter(object : Filter() {
            override fun shouldRun(description: Description): Boolean = description !in offCi

            override fun describe(): String = "tests that run off CI only"
        })
        super.run(notifier)
    }

    private fun leaves(description: Description): List<Description> =
        if (description.isTest) listOf(description) else description.children.flatMap(::leaves)
}
