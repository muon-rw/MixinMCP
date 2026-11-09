package com.llamalad7.mixinextras.injector.wrapoperation

import org.spongepowered.asm.mixin.injection.At

// Test-only stand-in for MixinExtras' @WrapOperation; see org.spongepowered.asm.mixin.MixinStubs.

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class WrapOperation(val method: Array<String> = [], val at: Array<At> = [])
