package com.jhaiian.lucky.data

data class QueueItem(val uri: String, val title: String?)

object PlayQueue {
    @Volatile
    var items: List<QueueItem> = emptyList()
        private set

    fun set(list: List<QueueItem>) {
        items = list
    }

    fun clear() {
        items = emptyList()
    }

    fun indexOf(uri: String): Int = items.indexOfFirst { it.uri == uri }

    fun next(uri: String, wrap: Boolean): QueueItem? {
        val index = indexOf(uri)
        if (index < 0) return null
        return items.getOrNull(index + 1) ?: if (wrap && items.size > 1) items.first() else null
    }

    fun previous(uri: String): QueueItem? {
        val index = indexOf(uri)
        if (index <= 0) return null
        return items.getOrNull(index - 1)
    }
}
