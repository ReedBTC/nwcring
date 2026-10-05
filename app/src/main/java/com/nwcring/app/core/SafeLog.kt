package com.nwcring.app.core

import android.util.Log

/**
 * The only place in the app that writes a log line. A build check fails if any other file
 * logs directly. Everything passes through [Redactor] first, and errors are recorded by
 * type only, never by message, because messages can carry data.
 */
object SafeLog {
    private const val TAG = "NwcRing"

    fun event(name: String, vararg facts: Pair<String, Any?>) {
        Log.i(TAG, Redactor.render(name, facts.toList()))
    }

    fun failure(name: String, error: Throwable) {
        Log.w(TAG, Redactor.render(name, listOf("error" to error.javaClass.simpleName)))
    }
}
