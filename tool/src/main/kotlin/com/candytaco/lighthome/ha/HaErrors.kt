package com.candytaco.lighthome.ha

/** Home Assistant sent something we don't understand. */
class HaProtocolException(message: String) : Exception(message)

/** The stored HA credentials no longer work; the user has to sign in again. */
class HaAuthRejectedException(message: String) : Exception(message)

/** An HTTP call to Home Assistant failed for a reason other than auth. */
class HaHttpException(val status: Int, message: String) : Exception(message)

/** A WebSocket command came back with `success: false`. */
class HaCommandException(val code: String, message: String) : Exception("$code: $message")

/** The WebSocket closed while a command or subscription was waiting on it. */
class HaConnectionClosedException(cause: Throwable? = null) :
    Exception("Connection to Home Assistant closed", cause)
