package com.shilapi.xcertplay.compat

/**
 * `ReflectiveOperationException` arrived in API 19. Android 4.2's verifier rejects a whole
 * class when a `catch` clause names a class it cannot resolve, which turns every reflective
 * call site into a `VerifyError` crash on those ROMs (the class fails to load at all, so it
 * cannot even be guarded at runtime).
 *
 * These sites classify reflection failures by walking the superclass chain by name instead.
 * The concrete exceptions (`NoSuchMethodException` and friends) exist on every supported
 * release, so the classification behaves the same on API 17 and on API 19+.
 */
private val REFLECTION_FAILURES = setOf(
    "java.lang.ReflectiveOperationException",
    "java.lang.ClassNotFoundException",
    "java.lang.IllegalAccessException",
    "java.lang.InstantiationException",
    "java.lang.NoSuchFieldException",
    "java.lang.NoSuchMethodException",
    "java.lang.reflect.InvocationTargetException",
)

fun Throwable.isReflectionFailure(): Boolean {
    var type: Class<*>? = javaClass
    while (type != null) {
        if (type.name in REFLECTION_FAILURES) return true
        type = type.superclass
    }
    return false
}
