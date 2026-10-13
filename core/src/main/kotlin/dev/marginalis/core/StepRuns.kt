package dev.marginalis.core

/** Splits steps already in step order into runs of consecutive steps in the same file; a step with no file is a run of its own. */
object StepRuns {

    fun <T> of(steps: List<T>, fileOf: (T) -> String?): List<List<T>> {
        val runs = mutableListOf<MutableList<T>>()
        for (step in steps) {
            val file = fileOf(step)
            val current = runs.lastOrNull()
            if (file != null && current != null && fileOf(current.last()) == file) current += step
            else runs += mutableListOf(step)
        }
        return runs
    }
}
