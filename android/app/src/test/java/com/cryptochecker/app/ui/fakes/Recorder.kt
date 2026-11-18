package com.cryptochecker.app.ui.fakes

import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/**
 * Umsetzung von [type] für Fakes mit grossen Schnittstellen: jeder Aufruf landet als «name(arg, …)» in [calls]. Methoden
 * ohne Rückgabe und suspend-Methoden geben Unit zurück; alles andere wirft — der Fake überschreibt es selbst.
 */
internal fun <T : Any> recorder(type: Class<T>, calls: MutableList<String>): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "toString" -> return@newProxyInstance "recorder(${type.simpleName})"
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
        }
        val params = args.orEmpty().filterNot { it is Continuation<*> }
        val suspending = args?.lastOrNull() is Continuation<*>
        if (!suspending && method.returnType != Void.TYPE) {
            throw UnsupportedOperationException("${type.simpleName}.${method.name} im Fake nicht umgesetzt")
        }
        calls += "${method.name}(${params.joinToString()})"
        Unit
    }
)
