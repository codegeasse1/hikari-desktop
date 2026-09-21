package androidx.compose.runtime

/**
 * Stub for Compose's `@Stable`. The Aniyomi source interfaces carry it as a
 * contract annotation for Compose consumers; on the desktop it compiles to
 * nothing, which is exactly what an annotation with no reader does.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class Stable
