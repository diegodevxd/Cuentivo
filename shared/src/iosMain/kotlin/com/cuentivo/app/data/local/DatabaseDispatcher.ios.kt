package com.cuentivo.app.data.local

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual val databaseDispatcher: CoroutineDispatcher = Dispatchers.IO
