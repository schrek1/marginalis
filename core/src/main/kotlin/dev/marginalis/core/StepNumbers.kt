package dev.marginalis.core

object StepNumbers {

    fun label(orders: Collection<Int>): String {
        val numbers = orders.toSortedSet().toList()
        val spans = numbers.fold(mutableListOf<IntRange>()) { spans, number ->
            val last = spans.lastOrNull()
            if (last != null && last.last + 1 == number) spans[spans.lastIndex] = last.first..number
            else spans += number..number
            spans
        }
        val noun = if (numbers.size == 1) "step" else "steps"
        return "$noun " + spans.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
    }
}
