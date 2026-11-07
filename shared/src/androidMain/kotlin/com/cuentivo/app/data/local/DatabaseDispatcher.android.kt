package com.cuentivo.app.data.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO
