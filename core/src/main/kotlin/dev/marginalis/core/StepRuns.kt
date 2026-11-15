package dev.marginalis.core

object StepRuns {

    fun <T> of(steps: List<T>, fileOf: (T) -> String?): List<List<T>> {
        val runs = mutableListOf<MutableList<T>>()
        for (step in steps) {
            val current = runs.lastOrNull()
            if (current != null && fileOf(current.last()) == fileOf(step)) current += step
            else runs += mutableListOf(step)
        }
        return runs
    }
}
