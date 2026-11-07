package com.cuentivo.app.backup

/** Why a restore was refused, in terms the screen can turn into one sentence. */
enum class BackupFailure {
    /** Not a Cuentivo backup at all, or truncated. */
    NOT_A_BACKUP,

    /** Made by a newer version of the app; this one would not understand the database. */
    NEWER_APP,

    /** Wrong password, or the file was altered after it was written. */
    WRONG_PASSWORD_OR_DAMAGED,
}

class BackupException(val failure: BackupFailure) : Exception(failure.name)
