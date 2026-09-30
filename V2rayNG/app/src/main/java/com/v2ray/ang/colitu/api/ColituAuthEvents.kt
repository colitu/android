package com.v2ray.ang.colitu.api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ColituAuthEvents {
    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authExpired = _authExpired.asSharedFlow()

    fun notifyAuthExpired() {
        _authExpired.tryEmit(Unit)
    }
}
