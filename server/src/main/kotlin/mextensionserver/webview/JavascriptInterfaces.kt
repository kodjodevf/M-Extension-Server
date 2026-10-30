package mextensionserver.webview

import android.webkit.JavascriptInterface
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Exposes objects passed to `WebView.addJavascriptInterface` the way Android
 * does: only their public methods annotated with [JavascriptInterface], with
 * JavaScript values converted to the parameter types.
 */
internal object JavascriptInterfaces {
    /** Method names a page can call on [target]. */
    fun methodNames(target: Any): List<String> = exposedMethods(target).map(Method::getName).distinct().sorted()

    /**
     * Calls [methodName] on [target] with [args] and returns the result as a
     * JSON value. Throws when no matching method exists or the method throws,
     * which the page sees as a JavaScript exception, as on Android.
     */
    fun invoke(
        target: Any,
        methodName: String,
        args: List<JsonElement>,
    ): JsonElement {
        val method =
            exposedMethods(target).firstOrNull { it.name == methodName && it.parameterCount == args.size }
                ?: throw NoSuchMethodException("Method not found: $methodName with ${args.size} argument(s)")
        method.isAccessible = true
        val converted = method.parameterTypes.mapIndexed { index, type -> convert(args[index], type) }
        val result =
            try {
                method.invoke(target, *converted.toTypedArray())
            } catch (error: InvocationTargetException) {
                throw error.targetException ?: error
            }
        return toJson(result)
    }

    private fun exposedMethods(target: Any): List<Method> {
        val publicMethods = target.javaClass.methods.filter { !Modifier.isStatic(it.modifiers) }
        val annotated = publicMethods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        if (annotated.isNotEmpty()) return annotated
        // An extension whose annotations did not survive conversion would
        // expose nothing at all; fall back to its own public methods.
        return publicMethods.filter { it.declaringClass != Any::class.java }
    }

    private fun convert(
        value: JsonElement,
        type: Class<*>,
    ): Any? {
        val primitive = value as? JsonPrimitive
        val text = primitive?.takeUnless { it is JsonNull }?.contentOrNull
        val number = primitive?.doubleOrNull
        return when (type) {
            String::class.java -> if (primitive == null) value.toString() else text
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> number?.toInt() ?: 0
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> number?.toLong() ?: 0L
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> (number?.toInt() ?: 0).toShort()
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> (number?.toInt() ?: 0).toByte()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> number ?: 0.0
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> number?.toFloat() ?: 0f
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> primitive?.booleanOrNull ?: false
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> text?.firstOrNull() ?: '\u0000'
            else -> null
        }
    }

    private fun toJson(value: Any?): JsonElement =
        when (value) {
            null, Unit -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            else -> JsonPrimitive(value.toString())
        }
}
