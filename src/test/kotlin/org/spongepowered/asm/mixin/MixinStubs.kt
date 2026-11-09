package org.spongepowered.asm.mixin

import kotlin.reflect.KClass

// Test-only stand-ins with Mixin's real names and retention, so fixtures compile to the same
// class-file annotations a shipped mixin carries. Mixin itself is not on the test classpath.

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class Mixin(val value: Array<KClass<*>> = [], val targets: Array<String> = [], val priority: Int = 1000)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Overwrite
