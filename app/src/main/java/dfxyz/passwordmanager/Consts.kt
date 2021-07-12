package dfxyz.passwordmanager

import java.io.File

const val CIPHER_TRANSFORMATION = "AES/ECB/NoPadding"

val DATA_FILE_HEADER = "PASSWORD_MANAGER".toByteArray()

val WORKING_DIR = File("/storage/emulated/0/Documents/dfxyz/PasswordManager")
val DATA_FILE = File(WORKING_DIR, "data")
val BACKUP_FILE = File(WORKING_DIR, "data.bak")

const val MIN_MASTER_PASSWORD_LENGTH = 4

val PASSWORD_TYPES = arrayListOf(NormalPassword(0), NumericPassword(0), ComplicatedPassword(0))

const val MIN_PASSWORD_LENGTH = 6
const val MAX_PASSWORD_LENGTH = 12
const val DEFAULT_PASSWORD_LENGTH = 8
const val DEFAULT_PASSWORD_LENGTH_POSITION = 2
val PASSWORD_LENGTHS = arrayListOf(6, 7, 8, 9, 10, 11, 12)
