package com.txfast.app

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * بروتوكول بسيط جدا فوق TCP خام:
 *  طلب (37 بايت): token(16) | cmd(1) | index(4) | offset(8) | length(8)
 *  cmd=0 قائمة الملفات: count(4) ثم لكل ملف nameLen(4) name size(8)
 *  cmd=1 جلب مدى من ملف: يرد بالبايتات الخام فقط
 */
const val CMD_LIST: Byte = 0
const val CMD_GET: Byte = 1
const val BUF = 4 * 1024 * 1024      // بفر السوكيت
const val CHUNK = 1024 * 1024        // بفر القراءة/الكتابة
const val WORKERS = 4                // اتصالات متوازية

class FileInfo(val name: String, val size: Long)

private fun ByteBuffer.fill(ch: SocketChannel): ByteBuffer {
    while (hasRemaining()) if (ch.read(this) < 0) throw EOFException()
    flip()
    return this
}

private fun writeAll(ch: SocketChannel, b: ByteBuffer) {
    while (b.hasRemaining()) ch.write(b)
}

object Net {
    /** عناوين IPv4 المحلية على الواي فاي / الهوتسبوت فقط (بدون شبكة الموبايل). */
    fun localIps(): List<String> {
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val ok = listOf("wlan", "ap", "swlan", "p2p", "eth")
        return Collections.list(ifs)
            .filter { it.isUp && !it.isLoopback && ok.any { p -> it.name.startsWith(p) } }
            .flatMap { Collections.list(it.inetAddresses) }
            .filter { it is Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }
}

/** مصدر ملف للإرسال (ملف عادي، Uri، أو APK). */
class Src(val name: String, val size: Long, val open: () -> ParcelFileDescriptor?)

class Sender(
    private val srcs: List<Src>,
    private val token: String
) {
    private var server: ServerSocketChannel? = null
    private val pool = Executors.newCachedThreadPool()
    private val open = ConcurrentLinkedQueue<SocketChannel>()
    @Volatile private var stopped = false
    private lateinit var infos: List<FileInfo>
    private lateinit var onBytes: (Long) -> Unit

    val totalSize: Long get() = infos.sumOf { it.size }

    fun start(onBytes: (Long) -> Unit): Int {
        this.onBytes = onBytes
        infos = srcs.map { FileInfo(it.name, it.size) }
        val ssc = ServerSocketChannel.open()
        ssc.bind(InetSocketAddress(0))
        server = ssc
        val t = Thread {
            while (!stopped) {
                try {
                    val ch = ssc.accept()
                    open.add(ch)
                    pool.execute { handle(ch) }
                } catch (e: Exception) {
                    if (stopped) break
                }
            }
        }
        t.isDaemon = true
        t.start()
        return ssc.socket().localPort
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        open.forEach { try { it.close() } catch (_: Exception) {} }
        pool.shutdown()
    }

    private fun handle(ch: SocketChannel) {
        try {
            ch.socket().tcpNoDelay = true
            ch.socket().sendBufferSize = BUF
            val req = ByteBuffer.allocate(37).fill(ch)
            val tk = ByteArray(16)
            req.get(tk)
            if (String(tk, Charsets.US_ASCII) != token) return
            when (req.get()) {
                CMD_LIST -> {
                    val names = infos.map { it.name.toByteArray(Charsets.UTF_8) }
                    var n = 4
                    names.forEach { n += 4 + it.size + 8 }
                    val out = ByteBuffer.allocate(n)
                    out.putInt(infos.size)
                    infos.forEachIndexed { i, f ->
                        out.putInt(names[i].size); out.put(names[i]); out.putLong(f.size)
                    }
                    out.flip()
                    writeAll(ch, out)
                }
                CMD_GET -> {
                    val idx = req.getInt()
                    var pos = req.getLong()
                    var left = req.getLong()
                    val pfd = srcs[idx].open() ?: return
                    pfd.use {
                        val fd: FileDescriptor = it.fileDescriptor
                        val buf = ByteBuffer.allocateDirect(CHUNK)
                        while (left > 0 && !stopped) {
                            buf.clear()
                            buf.limit(minOf(CHUNK.toLong(), left).toInt())
                            val n = Os.pread(fd, buf, pos)
                            if (n <= 0) throw EOFException()
                            buf.position(0)
                            buf.limit(n)
                            writeAll(ch, buf)
                            pos += n
                            left -= n
                            onBytes(n.toLong())
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { ch.close() } catch (_: Exception) {}
            open.remove(ch)
        }
    }
}

class Receiver(
    private val cr: ContentResolver,
    private val host: String,
    private val port: Int,
    private val token: String
) {
    private val sockets = ConcurrentLinkedQueue<SocketChannel>()
    @Volatile private var cancelled = false
    val saved = ArrayList<Pair<String, Uri>>()

    fun cancel() {
        cancelled = true
        sockets.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun connect(): SocketChannel {
        val ch = SocketChannel.open()
        sockets.add(ch)
        ch.socket().receiveBufferSize = BUF
        ch.socket().tcpNoDelay = true
        ch.socket().connect(InetSocketAddress(host, port), 8000)
        return ch
    }

    private fun request(ch: SocketChannel, cmd: Byte, idx: Int, off: Long, len: Long) {
        val b = ByteBuffer.allocate(37)
        b.put(token.toByteArray(Charsets.US_ASCII))
        b.put(cmd); b.putInt(idx); b.putLong(off); b.putLong(len)
        b.flip()
        writeAll(ch, b)
    }

    fun list(): List<FileInfo> {
        val ch = connect()
        try {
            request(ch, CMD_LIST, 0, 0, 0)
            val n = ByteBuffer.allocate(4).fill(ch).getInt()
            return (0 until n).map {
                val l = ByteBuffer.allocate(4).fill(ch).getInt()
                val nameB = ByteBuffer.allocate(l).fill(ch)
                val name = String(nameB.array(), 0, l, Charsets.UTF_8)
                val size = ByteBuffer.allocate(8).fill(ch).getLong()
                FileInfo(name, size)
            }
        } finally {
            try { ch.close() } catch (_: Exception) {}
        }
    }

    private class Job(
        val index: Int,
        val uri: Uri,
        val pfd: android.os.ParcelFileDescriptor,
        val parts: AtomicInteger
    )

    private class Part(val job: Job, val off: Long, val len: Long)

    fun download(infos: List<FileInfo>, onBytes: (Long) -> Unit) {
        val jobs = ArrayList<Job>()
        val parts = ArrayList<Part>()
        try {
            infos.forEachIndexed { i, f ->
                val safe = f.name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/TX")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IOException("مش قادر أنشئ الملف: $safe")
                saved.add(safe to uri)
                val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IOException("مش قادر أفتح الملف: $safe")
                val n = if (f.size >= (32L shl 20)) WORKERS else if (f.size >= (8L shl 20)) 2 else 1
                val job = Job(i, uri, pfd, AtomicInteger(if (f.size == 0L) 0 else n))
                jobs.add(job)
                if (f.size == 0L) {
                    finish(job)
                } else {
                    val each = (f.size + n - 1) / n
                    var off = 0L
                    while (off < f.size) {
                        parts.add(Part(job, off, minOf(each, f.size - off)))
                        off += each
                    }
                    job.parts.set(parts.count { it.job === job })
                }
            }
            val pool = Executors.newFixedThreadPool(WORKERS)
            try {
                val fs = parts.map { p -> pool.submit { fetch(p, onBytes) } }
                for (f in fs) f.get()
            } catch (e: Exception) {
                cancel()
                pool.shutdown()
                pool.awaitTermination(5, TimeUnit.SECONDS)
                throw (e.cause ?: e)
            } finally {
                pool.shutdown()
            }
        } catch (e: Exception) {
            jobs.forEach {
                try { it.pfd.close() } catch (_: Exception) {}
                try { cr.delete(it.uri, null, null) } catch (_: Exception) {}
            }
            throw e
        }
    }

    private fun finish(job: Job) {
        try { job.pfd.close() } catch (_: Exception) {}
        val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        cr.update(job.uri, cv, null, null)
    }

    private fun fetch(p: Part, onBytes: (Long) -> Unit) {
        val ch = connect()
        try {
            request(ch, CMD_GET, p.job.index, p.off, p.len)
            val fd = p.job.pfd.fileDescriptor
            val buf = ByteBuffer.allocateDirect(CHUNK)
            var pos = p.off
            var left = p.len
            while (left > 0) {
                if (cancelled) throw IOException("تم الإلغاء")
                buf.clear()
                buf.limit(minOf(CHUNK.toLong(), left).toInt())
                val n = ch.read(buf)
                if (n < 0) throw EOFException("انقطع الاتصال")
                var w = 0
                while (w < n) {
                    buf.limit(n)
                    buf.position(w)
                    w += Os.pwrite(fd, buf, pos + w)
                }
                pos += n
                left -= n
                onBytes(n.toLong())
            }
        } finally {
            try { ch.close() } catch (_: Exception) {}
        }
        if (p.job.parts.decrementAndGet() == 0) finish(p.job)
    }
}
