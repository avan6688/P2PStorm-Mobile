package com.p2pstorm.sdk.utils

import com.p2pstorm.sdk.CoreEventMap
import com.p2pstorm.sdk.interop.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class EventEmitter {
    private val listeners = ConcurrentHashMap<CoreEventMap<*>, CopyOnWriteArrayList<EventListener<*>>>()

    fun <T> addEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        val list = listeners.getOrPut(event) { CopyOnWriteArrayList() }
        list.add(listener)
    }

    fun <T> emit(
        event: CoreEventMap<T>,
        data: T,
    ) {
        listeners[event]?.forEach { listener ->
            @Suppress("UNCHECKED_CAST")
            (listener as EventListener<T>).onEvent(data)
        }
    }

    fun <T> removeEventListener(
        event: CoreEventMap<T>,
        listener: EventListener<T>,
    ) {
        listeners[event]?.remove(listener)
    }

    fun <T> hasListeners(event: CoreEventMap<T>): Boolean = listeners[event]?.isNotEmpty() ?: false

    fun removeAllListeners() {
        listeners.clear()
    }
}
