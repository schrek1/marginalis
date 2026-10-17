package dev.marginalis.plugin.testing

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class AllowReflection(val reason: String)
