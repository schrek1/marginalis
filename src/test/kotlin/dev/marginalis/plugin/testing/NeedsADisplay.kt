package dev.marginalis.plugin.testing

import org.junit.internal.runners.JUnit38ClassRunner
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import org.junit.runner.notification.RunNotifier
import java.awt.GraphicsEnvironment

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DisplayOnly

class NeedsADisplay(testClass: Class<*>) : JUnit38ClassRunner(testClass) {

    override fun run(notifier: RunNotifier) {
        if (!GraphicsEnvironment.isHeadless()) return super.run(notifier)
        val everyTest = leaves(description)
        val marked = everyTest.filter { it.getAnnotation(DisplayOnly::class.java) != null }
        val skipped = marked.ifEmpty { everyTest }
        skipped.forEach(notifier::fireTestIgnored)
        if (skipped.size == everyTest.size) return
        filter(object : Filter() {
            override fun shouldRun(description: Description): Boolean = description !in skipped

            override fun describe(): String = "tests that run headless"
        })
        super.run(notifier)
    }

    private fun leaves(description: Description): List<Description> =
        if (description.isTest) listOf(description) else description.children.flatMap(::leaves)
}
