package dfxyz.passwordmanager

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor
import kotlin.math.min
import kotlin.random.Random

class MainViewModel : ViewModel() {
    val entryList: LiveData<List<CharSequence>> = MutableLiveData()

    private var initialized = false
    private val counter = UInt128()
    private lateinit var cipher: Cipher
    private lateinit var dataFile: RandomAccessFile
    private val passwordMap = HashMap<CharSequence, CharSequence>()

    fun onCreateView() = runCatching { onCreateView0() }

    private fun onCreateView0(): CheckShowMasterPasswordDialogResult {
        if (initialized) {
            return CheckShowMasterPasswordDialogResult.IGNORE
        }

        if (DATA_FILE.exists()) {
            return CheckShowMasterPasswordDialogResult.SHOW_FOR_LOADING
        }
        return CheckShowMasterPasswordDialogResult.SHOW_FOR_INITIALIZING
    }

    fun onEnterMasterPasswordEnter(masterPassword: CharSequence) =
        runCatching { onMasterPasswordEntered0(masterPassword) }

    @SuppressLint("GetInstance")
    private fun onMasterPasswordEntered0(masterPassword: CharSequence): EnterMasterPasswordResult {
        if (masterPassword.isEmpty()) {
            throw IllegalArgumentException()
        }
        val secretKey = run {
            val hasher = MessageDigest.getInstance("SHA1")
            val hash = hasher.digest(masterPassword.toString().toByteArray())
            val bytes = hash.copyOf(16)
            SecretKeySpec(bytes, "AES")
        }
        cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }

        if (DATA_FILE.exists()) {
            return loadDataFile()
        }
        return createDataFile()
    }

    private fun loadDataFile(): EnterMasterPasswordResult {
        dataFile = RandomAccessFile(DATA_FILE, "rw")
        val len = dataFile.length()
        if (len < 32) {
            dataFile.close()
            return EnterMasterPasswordResult.DATA_FILE_CORRUPTED
        }
        val buffer = ByteArray(len.toInt())
        dataFile.read(buffer)

        val counterBytes = buffer.copyOf(16)
        counter.set(counterBytes)

        // validate the master password
        val headerCipherText = buffer.copyOfRange(16, 32)
        val headerPlainText = cipherTransform(headerCipherText)
        if (!headerPlainText.contentEquals(DATA_FILE_HEADER)) {
            dataFile.close()
            return EnterMasterPasswordResult.INVALID_MASTER_PASSWORD
        }

        var fileNeedCompact = false
        var i = 32
        while (i < len) {
            // parse entry name
            val entryNameLen = buffer[i].toUByte().toInt()
            val entryNameCipherText = run {
                val start = i + 1
                val end = start + entryNameLen
                if (end > len) {
                    dataFile.close()
                    return EnterMasterPasswordResult.DATA_FILE_CORRUPTED
                }
                buffer.copyOfRange(start, end)
            }
            val entryName = String(cipherTransform(entryNameCipherText))

            i += 1 + entryNameLen
            if (i >= len) {
                dataFile.close()
                return EnterMasterPasswordResult.DATA_FILE_CORRUPTED
            }

            // parse password
            val passwordLen = buffer[i].toUByte().toInt()
            if (passwordLen == 0) {
                // tombstone
                passwordMap.remove(entryName)
                fileNeedCompact = true
                i += 1
                continue
            }
            val passwordCipherText = run {
                val start = i + 1
                val end = start + passwordLen
                if (end > len) {
                    dataFile.close()
                    return EnterMasterPasswordResult.DATA_FILE_CORRUPTED
                }
                buffer.copyOfRange(start, end)
            }
            val password = String(cipherTransform(passwordCipherText))

            if (passwordMap.put(entryName, password) != null) {
                fileNeedCompact = true
            }
            i += 1 + passwordLen
        }
        if (fileNeedCompact) {
            DATA_FILE.copyTo(BACKUP_FILE, true)
            writeDataFile()
        }

        postEntryListChange()
        return EnterMasterPasswordResult.OK
    }

    private fun createDataFile(): EnterMasterPasswordResult {
        if (!WORKING_DIR.exists()) {
            WORKING_DIR.mkdirs()
        }
        dataFile = RandomAccessFile(DATA_FILE, "rw")
        writeDataFile()
        return EnterMasterPasswordResult.OK
    }

    private fun writeDataFile() {
        dataFile.seek(0)
        dataFile.setLength(0)

        // write counter
        counter.randomize()
        dataFile.write(counter.toByteArray())

        // write header
        dataFile.write(cipherTransform(DATA_FILE_HEADER))

        // write all passwords
        for ((entryName, password) in passwordMap) {
            cipherTransform(entryName.toString().toByteArray()).also {
                if (it.size > UByte.MAX_VALUE.toInt()) {
                    throw IllegalStateException()
                }
                val len = it.size.toByte()
                dataFile.write(byteArrayOf(len))
                dataFile.write(it)
            }
            cipherTransform(password.toString().toByteArray()).also {
                if (it.isEmpty() || it.size > UByte.MAX_VALUE.toInt()) {
                    throw IllegalStateException()
                }
                val len = it.size.toByte()
                dataFile.write(byteArrayOf(len))
                dataFile.write(it)
            }
        }
    }

    private fun postEntryListChange() {
        val entryNames = ArrayList(passwordMap.keys).apply { sortBy { it.toString() } }
        (entryList as MutableLiveData<List<CharSequence>>).postValue(entryNames)
    }

    fun onClickPasswordEntry(entryName: CharSequence): PasswordEntryInfo? {
        return passwordMap[entryName]?.let {
            PasswordEntryInfo(entryName, it)
        }
    }

    fun onUpdatePasswordEntry(entryName: CharSequence, password: CharSequence, expectEntryExisted: Boolean): Boolean {
        if (entryName.isEmpty() || entryName.length > UByte.MAX_VALUE.toInt()) {
            throw IllegalArgumentException()
        }
        if (password.isEmpty() || password.length > UByte.MAX_VALUE.toInt()) {
            throw IllegalArgumentException()
        }
        if (expectEntryExisted && !passwordMap.containsKey(entryName)) {
            return false
        }
        if (!expectEntryExisted && passwordMap.containsKey(entryName)) {
            return false
        }

        passwordMap[entryName] = password

        cipherTransform(entryName.toString().toByteArray()).also {
            val len = it.size.toByte()
            dataFile.write(byteArrayOf(len))
            dataFile.write(it)
        }
        cipherTransform(password.toString().toByteArray()).also {
            val len = it.size.toByte()
            dataFile.write(byteArrayOf(len))
            dataFile.write(it)
        }

        if (!expectEntryExisted) {
            postEntryListChange()
        }

        return true
    }

    fun onRemovePasswordEntry(entryName: CharSequence): Boolean {
        if (entryName.isEmpty() || entryName.length > UByte.MAX_VALUE.toInt()) {
            throw IllegalArgumentException()
        }
        if (passwordMap.remove(entryName) == null) {
            return false
        }

        cipherTransform(entryName.toString().toByteArray()).also {
            val len = it.size.toByte()
            dataFile.write(byteArrayOf(len))
            dataFile.write(it)
        }
        dataFile.write(byteArrayOf(0))

        postEntryListChange()
        return true
    }

    fun onGeneratePassword(passwordOption: PasswordOption): CharSequence {
        if (passwordOption.len < MIN_PASSWORD_LENGTH || passwordOption.len > MAX_PASSWORD_LENGTH) {
            throw IllegalArgumentException()
        }
        return when (passwordOption) {
            is NumericPassword -> generatePureNumberPassword(passwordOption.len)
            is NormalPassword -> generateNormalPassword(passwordOption.len)
            is ComplicatedPassword -> generateNormalPassword(passwordOption.len, true)
        }
    }

    private fun generateNormalPassword(len: Int, addSpecialChar: Boolean = false): CharSequence {
        val random = Random(System.currentTimeMillis())
        val codePoints = IntArray(len).also {
            // at least one of each element
            it[0] = randomCodePoint(random, '0', 10)
            it[1] = randomCodePoint(random, 'a', 26)
            it[2] = randomCodePoint(random, 'A', 26)
            if (addSpecialChar) {
                it[3] = '!'.code
            }
        }
        for (i in if (addSpecialChar) {
            4
        } else {
            3
        } until len) {
            val code = when (random.nextInt(3)) {
                0 -> randomCodePoint(random, '0', 10)
                1 -> randomCodePoint(random, 'a', 26)
                2 -> randomCodePoint(random, 'A', 26)
                else -> throw IllegalStateException()
            }
            codePoints[i] = code
        }
        codePoints.shuffle(random)
        return String(codePoints, 0, len)
    }

    private fun generatePureNumberPassword(len: Int): CharSequence {
        val random = Random(System.currentTimeMillis())
        val codePoints = IntArray(len) { randomCodePoint(random, '0', 10) }
        return String(codePoints, 0, len)
    }

    private fun randomCodePoint(random: Random, baseChar: Char, range: Int): Int = baseChar.code + random.nextInt(range)

    override fun onCleared() {
        if (!initialized) {
            return
        }

        initialized = false
        passwordMap.clear()
        dataFile.close()
    }

    private fun cipherTransform(source: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        val len = source.size
        var start = 0
        while (start < len) {
            val blockLen = min(len - start, 16)

            val bytes = cipher.doFinal(counter.toByteArray())
            counter.increase()

            for (i in 0 until blockLen) {
                result.add(bytes[i] xor source[start + i])
            }

            start += 16
        }
        return result.toByteArray()
    }
}

enum class CheckShowMasterPasswordDialogResult {
    IGNORE,
    SHOW_FOR_INITIALIZING,
    SHOW_FOR_LOADING,
}

enum class EnterMasterPasswordResult {
    OK,
    INVALID_MASTER_PASSWORD,
    DATA_FILE_CORRUPTED,
}

data class PasswordEntryInfo(val entryName: CharSequence, val password: CharSequence)

private data class UInt128(private var h64: Long = 0L, private var l64: Long = 0L) {
    fun set(bytes: ByteArray) {
        if (bytes.size != 16) {
            throw IllegalArgumentException()
        }

        l64 = 0L
        h64 = 0L
        for (i in 0 until 8) {
            l64 += bytes[i].toUByte().toLong().shl(i * 8)
            h64 += bytes[i + 8].toUByte().toLong().shl(i * 8)
        }
    }

    fun randomize() {
        val random = Random(System.currentTimeMillis())
        h64 = random.nextLong()
        l64 = random.nextLong()
    }

    fun increase() {
        if (++l64 == 0L) {
            ++h64
        }
    }

    fun toByteArray(): ByteArray {
        return ByteArray(16) { i ->
            if (i < 8) {
                (l64.shr(i * 8) and 0xFF).toByte()
            } else {
                (h64.shr((i - 8) * 8) and 0xFF).toByte()
            }
        }
    }
}

sealed class PasswordOption(val len: Int) {
    abstract fun new(len: Int): PasswordOption
}
class NormalPassword(len: Int) : PasswordOption(len) {
    override fun new(len: Int): PasswordOption {
        return NormalPassword(len)
    }

    override fun toString(): String {
        return "Normal"
    }
}
class NumericPassword(len: Int) : PasswordOption(len) {
    override fun new(len: Int): PasswordOption {
        return NumericPassword(len)
    }

    override fun toString(): String {
        return "Numeric"
    }
}
class ComplicatedPassword(len: Int) : PasswordOption(len) {
    override fun new(len: Int): PasswordOption {
        return ComplicatedPassword(len)
    }

    override fun toString(): String {
        return "Complicated"
    }
}
