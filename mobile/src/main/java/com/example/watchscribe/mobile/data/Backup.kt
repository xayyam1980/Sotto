package com.example.watchscribe.mobile.data

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-protected backup of all notes into one file the user keeps wherever they like
 * (a folder, USB stick, their own cloud). Nothing is uploaded by Sotto.
 *
 * File: "SOTTOBK1" · salt(16) · iterations(int) · nonce(12) · chunks of [length(int) · AES-256-GCM(ciphertext)], length 0 ends.
 * The key comes from the password with PBKDF2-HMAC-SHA256. Each 1 MB chunk has its own nonce (base nonce + counter),
 * so big backups never need to fit in memory and any change to the file is detected.
 */
object BackupCrypto {
    private val MAGIC = "SOTTOBK1".toByteArray()
    private const val ITERATIONS = 200_000
    private const val CHUNK = 1 shl 20

    class WrongPassword : Exception("Wrong password, or the file is damaged")

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun nonce(base: ByteArray, counter: Int) = base.copyOf().also { n ->
        for (i in 0 until 4) n[8 + i] = (n[8 + i].toInt() xor (counter ushr (8 * (3 - i)))).toByte()
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray, iterations: Int = ITERATIONS) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val base = ByteArray(12).also(rnd::nextBytes)
        val k = key(password, salt, iterations)
        val out = DataOutputStream(output)
        out.write(MAGIC); out.write(salt); out.writeInt(iterations); out.write(base)
        val buf = ByteArray(CHUNK)
        var counter = 0
        while (true) {
            var n = 0
            while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
            if (n == 0) break
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(base, counter++)))
            c.updateAAD(byteArrayOf(0)) // marks a data chunk
            val ct = c.doFinal(buf, 0, n)
            out.writeInt(ct.size); out.write(ct)
            if (n < CHUNK) break
        }
        // closing chunk proves the file wasn't cut short
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(base, counter)))
        c.updateAAD(byteArrayOf(1))
        val end = c.doFinal(ByteArray(0))
        out.writeInt(end.size); out.write(end)
        out.writeInt(0)
        out.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val ins = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { ins.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("Not a Sotto backup file")
        val salt = ByteArray(16).also { ins.readFully(it) }
        val iterations = ins.readInt().also { require(it in 1_000..10_000_000) { "Not a Sotto backup file" } }
        val base = ByteArray(12).also { ins.readFully(it) }
        val k = key(password, salt, iterations)
        var counter = 0
        var ended = false
        try {
            while (true) {
                val len = ins.readInt()
                if (len == 0) break
                require(len in 16..(CHUNK + 16)) { "Damaged backup file" }
                val ct = ByteArray(len).also { ins.readFully(it) }
                if (len == 16) { // closing chunk
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(base, counter)))
                    c.updateAAD(byteArrayOf(1))
                    c.doFinal(ct)
                    ended = true
                    continue
                }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(base, counter++)))
                c.updateAAD(byteArrayOf(0))
                output.write(c.doFinal(ct))
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        } catch (e: EOFException) {
            throw IllegalArgumentException("The backup file is incomplete")
        }
        if (!ended) throw IllegalArgumentException("The backup file is incomplete")
        output.flush()
    }
}

/** Builds and restores backups of the notes on this phone. */
object Backup {
    private val SAFE = Regex("^(chunks|raw|meta|digests|phone_notes)/[A-Za-z0-9_.\\-]+$|^people\\.json$")

    /** Writes an encrypted backup to [uri]. Returns the number of notes saved. */
    fun create(ctx: Context, uri: Uri, password: CharArray, withAudio: Boolean): Int {
        val zip = File(ctx.cacheDir, "backup.zip")
        var notes = 0
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { z ->
                fun add(name: String, f: File) {
                    z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
                val root = ctx.filesDir
                File(root, "chunks").listFiles { f -> f.name.endsWith(".wav") }.orEmpty().forEach { f ->
                    notes++
                    if (withAudio) add("chunks/${f.name}", f)
                    else { z.putNextEntry(ZipEntry("chunks/${f.name}")); z.write(Wav.header(16_000, 0)); z.closeEntry() }
                }
                listOf("meta", "digests", "phone_notes").forEach { d ->
                    File(root, d).listFiles().orEmpty().filter { it.isFile }.forEach { add("$d/${it.name}", it) }
                }
                File(root, "people.json").takeIf { it.exists() }?.let { add("people.json", it) }
                if (!withAudio) {
                    // notes restored from a text-only backup are marked as having no audio
                    z.putNextEntry(ZipEntry("meta/.textonly")); z.closeEntry()
                }
            }
            ctx.contentResolver.openOutputStream(uri)!!.buffered().use { out ->
                zip.inputStream().buffered().use { BackupCrypto.encrypt(it, out, password) }
            }
        } finally {
            zip.delete()
        }
        return notes
    }

    /** Restores a backup from [uri]; notes already on the phone are kept as they are. Returns notes added. */
    fun restore(ctx: Context, uri: Uri, password: CharArray): Int {
        val zip = File(ctx.cacheDir, "restore.zip")
        var added = 0
        try {
            ctx.contentResolver.openInputStream(uri)!!.buffered().use { ins ->
                zip.outputStream().buffered().use { BackupCrypto.decrypt(ins, it, password) }
            }
            val root = ctx.filesDir
            var textOnly = false
            val newIds = mutableListOf<String>()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == "meta/.textonly") { textOnly = true; continue }
                    if (!SAFE.matches(e.name)) continue // never write outside Sotto's own folders
                    val target = File(root, e.name)
                    if (target.exists()) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { z.copyTo(it) }
                    if (e.name.startsWith("chunks/")) { added++; newIds += target.name }
                }
            }
            if (textOnly) newIds.forEach { id ->
                val m = File(root, "meta/$id.json")
                if (m.exists()) runCatching { m.writeText(org.json.JSONObject(m.readText()).put("audioDeleted", true).toString()) }
            }
        } finally {
            zip.delete()
        }
        People.invalidate()
        RecordingStore.rematchAll(ctx)
        return added
    }
}
