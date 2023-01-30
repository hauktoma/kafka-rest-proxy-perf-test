package de.enbw.kafka.perftest.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Extensions for lazy logging a message
 */

inline fun Logger.error(throwable: Throwable? = null, crossinline message: () -> String) {
    if (isErrorEnabled) error(message(), throwable)
}

inline fun Logger.warn(throwable: Throwable? = null, crossinline message: () -> String) {
    if (isWarnEnabled) warn(message(), throwable)
}

inline fun Logger.info(throwable: Throwable? = null, crossinline message: () -> String) {
    if (isInfoEnabled) info(message(), throwable)
}

inline fun Logger.debug(throwable: Throwable? = null, crossinline message: () -> String) {
    if (isDebugEnabled) debug(message(), throwable)
}

inline fun Logger.trace(throwable: Throwable? = null, crossinline message: () -> String) {
    if (isTraceEnabled) trace(message(), throwable)
}

fun Logger.error(any: Any?): Unit = error(any.toString())

fun Logger.warn(any: Any?): Unit = warn(any.toString())

fun Logger.info(any: Any?): Unit = info(any.toString())

fun Logger.debug(any: Any?): Unit = debug(any.toString())

fun Logger.trace(any: Any?): Unit = trace(any.toString())

/**
 * Use for toplevel logger without class
 *
 * private val log by logger {}
 */
fun logger(lambda: () -> Unit): Lazy<Logger> =
    lazy { LoggerFactory.getLogger(lambda.javaClass.name.replace(Regex("""\$.*$"""), "")) }

/**
 * Directly use given class.
 */
fun logger(type: KClass<*>): Logger = LoggerFactory.getLogger(type.java.name.replace(Regex("""\$.*$"""), ""))
