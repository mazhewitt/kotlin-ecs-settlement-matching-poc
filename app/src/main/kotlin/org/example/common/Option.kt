package org.example.common

sealed class Option<out T> {
    object None : Option<Nothing>()
    data class Some<out T>(val value: T) : Option<T>()
    
    inline fun <R> fold(ifEmpty: () -> R, ifSome: (value: T) -> R): R = when (this) {
        is None -> ifEmpty()
        is Some -> ifSome(value)
    }
    
    inline fun <R> map(transform: (value: T) -> R): Option<R> = when (this) {
        is None -> None
        is Some -> Some(transform(value))
    }
    
    fun getOrNull(): T? = when (this) {
        is None -> null
        is Some -> value
    }
    
    companion object {
        fun <T> fromNullable(value: T?): Option<T> = if (value != null) Some(value) else None
    }
}