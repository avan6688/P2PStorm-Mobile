package com.p2pstorm.sdk.interop

fun interface EventListener<T> {
    fun onEvent(data: T)
}
