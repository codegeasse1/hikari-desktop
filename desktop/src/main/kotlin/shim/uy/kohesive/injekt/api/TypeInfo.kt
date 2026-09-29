package uy.kohesive.injekt.api

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

@Suppress("UNCHECKED_CAST")
fun Type.erasedType(): Class<Any> {
    return when (this) {
        is Class<*> -> this as Class<Any>
        is ParameterizedType -> this.rawType.erasedType()
        is GenericArrayType -> {
            val elementType = this.genericComponentType.erasedType()
            val testArray = java.lang.reflect.Array.newInstance(elementType, 0)
            testArray.javaClass
        }
        is TypeVariable<*> -> {
            throw IllegalStateException("Not sure what to do here yet")
        }
        is WildcardType -> {
            this.upperBounds[0].erasedType()
        }
        else -> throw IllegalStateException("Should not get here.")
    }
}

inline fun <reified T : Any> typeRef(): FullTypeReference<T> = object : FullTypeReference<T>() {}
inline fun <reified T : Any> fullType(): FullTypeReference<T> = object : FullTypeReference<T>() {}

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> : TypeReference<T> {
    override val type: Type = javaClass.genericSuperclass.let { superClass ->
        if (superClass is Class<*>) {
            throw IllegalArgumentException("Internal error: TypeReference constructed without actual type information")
        }
        (superClass as ParameterizedType).actualTypeArguments[0]
    }
}
