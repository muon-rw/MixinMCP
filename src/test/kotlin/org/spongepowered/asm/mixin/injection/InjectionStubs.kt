package org.spongepowered.asm.mixin.injection

// Test-only stand-ins; see MixinStubs.kt.

@Retention(AnnotationRetention.RUNTIME)
annotation class At(val value: String, val target: String = "")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Inject(val method: Array<String> = [], val at: Array<At> = [], val cancellable: Boolean = false)
