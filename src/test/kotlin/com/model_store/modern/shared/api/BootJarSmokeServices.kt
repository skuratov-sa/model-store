package com.model_store.modern.shared.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A deliberately narrow S3 startup protocol and SMTP handshake, with no external services. */
internal class BootJarSmokeServices : AutoCloseable {
    data class Checkpoint(val requests: Int, val smtpHandshakes: Int)

    private val buckets = listOf("smoke-participant", "smoke-product", "smoke-order", "smoke-system")
    private val publicBuckets = buckets.filter { it != "smoke-order" }
    private val requests = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<String>()
    private val handshakes = AtomicInteger()
    private val mapper = ObjectMapper()
    private val watchdog = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "smoke-socket-deadlines").apply { isDaemon = true }
    }
    private var s3: LoopbackServer? = null
    private var smtp: LoopbackServer? = null

    init {
        // use{} only owns successfully constructed fixtures; partial startup must close itself.
        try {
            s3 = LoopbackServer("S3", watchdog, failures, ::serveS3)
            smtp = LoopbackServer("SMTP", watchdog, failures, ::serveSmtp)
        } catch (failure: Throwable) {
            runCatching { close() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    fun properties(): List<String> = listOf(
        "--s3.endpoint=http://127.0.0.1:" + s3!!.port, "--s3.region=us-east-1",
        "--s3.access-key=smoke-access-key", "--s3.secret-key=smoke-secret-key",
        // Both production builders hard-code path style; accepted raw paths verify it below.
        "--s3.path-style-access-enabled=true",
        "--s3.participant-bucket-name=" + buckets[0], "--s3.product-bucket-name=" + buckets[1],
        "--s3.order-bucket-name=" + buckets[2], "--s3.system-bucket-name=" + buckets[3],
        "--s3.error-bucket-name=smoke-error", "--app.cdn-base-url=http://127.0.0.1:" + s3!!.port,
        "--spring.mail.host=127.0.0.1", "--spring.mail.port=" + smtp!!.port,
        "--spring.mail.username=smoke-user", "--spring.mail.password=smoke-password",
        "--spring.mail.protocol=smtp", "--spring.mail.test-connection=true",
        "--spring.mail.properties.mail.smtp.auth=false",
        "--spring.mail.properties.mail.smtp.starttls.enable=false",
        "--spring.mail.properties.mail.smtp.starttls.required=false",
        "--spring.mail.properties.mail.smtp.ssl.enable=false",
        "--spring.mail.properties.mail.smtp.ssl.trust=127.0.0.1",
        "--spring.mail.properties.mail.smtp.connectiontimeout=2000",
        "--spring.mail.properties.mail.smtp.timeout=2000",
        "--spring.mail.properties.mail.smtp.writetimeout=2000",
        "--app.email-from=smoke@example.test", "--app.email-reply-to=smoke@example.test",
    )

    fun checkpoint() = Checkpoint(requests.size, handshakes.get())

    fun assertStartup(profile: String, checkpoint: Checkpoint) {
        val expected = if (profile == "legacy") buckets.map { "GET /$it?acl" } +
            publicBuckets.map { "PUT /$it?policy" } else listOf("GET /")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline &&
            (!requests.drop(checkpoint.requests).containsAll(expected) || handshakes.get() <= checkpoint.smtpHandshakes)) {
            Thread.sleep(50)
        }
        assertTrue(requests.drop(checkpoint.requests).containsAll(expected), "$profile S3 startup calls missing")
        assertTrue(handshakes.get() > checkpoint.smtpHandshakes, "$profile SMTP handshake did not finish")
        assertTrue(failures.isEmpty(), "Unexpected fixture traffic: $failures")
    }

    private fun serveSmtp(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun reply(line: String) { socket.getOutputStream().write("$line\r\n".toByteArray(Charsets.US_ASCII)) }
        reply("220 smoke.test ESMTP")
        val hello = readLine(input, 512)
        check(hello.startsWith("EHLO ") || hello.startsWith("HELO ")) { "Expected SMTP greeting" }
        reply("250 smoke.test")
        check(readLine(input, 512) == "QUIT") { "Expected SMTP QUIT" }
        reply("221 Bye")
        handshakes.incrementAndGet()
    }

    private fun serveS3(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input, 1_024).split(' ')
        check(request.size == 3 && request[2] == "HTTP/1.1") { "Invalid HTTP request" }
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, 4_096)
            if (line.isEmpty()) break
            headerBytes += line.length
            check(headerBytes <= 16_384 && ':' in line) { "Invalid HTTP headers" }
            val name = line.substringBefore(':').lowercase()
            check(headers.put(name, line.substringAfter(':').trim()) == null) { "Duplicate HTTP header" }
        }
        val (method, target) = request
        // Literal paths only, with the SDK's optional trailing slash. No URL decoding.
        val bucket = buckets.singleOrNull { target == "/$it?acl" || target == "/$it/?acl" ||
            target == "/$it?policy" || target == "/$it/?policy" }
        val list = method == "GET" && target == "/"
        val acl = method == "GET" && bucket != null && target.endsWith("?acl")
        val policy = method == "PUT" && bucket in publicBuckets && target.endsWith("?policy")
        val credential = Regex("AWS4-HMAC-SHA256 Credential=smoke-access-key/(\\d{8})/us-east-1/s3/aws4_request, " +
            "SignedHeaders=([a-z0-9;-]+), Signature=[0-9a-f]{64}").matchEntire(headers["authorization"].orEmpty())
        val signedHeaders = credential?.groupValues?.get(2)?.split(';').orEmpty()
        val date = headers["x-amz-date"].orEmpty()
        val allowed = (list || acl || policy) && credential != null &&
            signedHeaders.containsAll(listOf("host", "x-amz-date")) &&
            date.matches(Regex("\\d{8}T\\d{6}Z")) && date.startsWith(credential.groupValues[1]) &&
            headers["host"] == "127.0.0.1:" + s3!!.port && headers["transfer-encoding"] == null
        val lengthText = headers["content-length"]
        val length = if (lengthText == null) 0 else lengthText.toIntOrNull() ?: -1
        if (!allowed || length !in 0..8_192 || (!policy && length != 0)) {
            failures.add("Rejected S3 method/path, credential scope or framing")
            respond(socket, 400)
            return
        }
        if (headers["expect"].equals("100-continue", ignoreCase = true)) {
            socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }
        val body = input.readNBytes(length)
        check(body.size == length) { "Truncated S3 body" }
        if (policy && mapper.readTree(body) != mapper.readTree("""
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
             "Action":"s3:GetObject","Resource":"arn:aws:s3:::$bucket/*"}]}
        """)) {
            failures.add("Rejected S3 policy body")
            respond(socket, 400)
            return
        }
        requests.add(when { list -> "GET /"; acl -> "GET /$bucket?acl"; else -> "PUT /$bucket?policy" })
        val xml = when {
            list -> "<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                "<Owner><ID>smoke</ID><DisplayName>smoke</DisplayName></Owner><Buckets>" +
                buckets.joinToString("") { "<Bucket><Name>$it</Name><CreationDate>2026-01-01T00:00:00Z</CreationDate></Bucket>" } +
                "</Buckets></ListAllMyBucketsResult>"
            acl -> "<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                "<Owner><ID>smoke</ID><DisplayName>smoke</DisplayName></Owner><AccessControlList><Grant>" +
                "<Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"CanonicalUser\">" +
                "<ID>smoke</ID><DisplayName>smoke</DisplayName></Grantee><Permission>FULL_CONTROL</Permission>" +
                "</Grant></AccessControlList></AccessControlPolicy>"
            else -> ""
        }
        respond(socket, if (policy) 204 else 200, xml)
    }

    private fun respond(socket: Socket, status: Int, xml: String = "") {
        val body = xml.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; else -> "Bad Request" }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: application/xml\r\n" +
            "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII) + body)
    }

    override fun close() = closeAll(
        { s3?.close() }, { smtp?.close() },
        { watchdog.shutdownNow(); check(watchdog.awaitTermination(5, TimeUnit.SECONDS)) { "Watchdog did not stop" } },
        { assertTrue(failures.isEmpty(), "Unexpected fixture traffic: $failures") },
    )

    private class LoopbackServer(
        private val label: String,
        private val watchdog: ScheduledExecutorService,
        private val failures: MutableList<String>,
        private val serve: (Socket) -> Unit,
    ) : AutoCloseable {
        private val listener = ServerSocket().also {
            try {
                it.bind(InetSocketAddress("127.0.0.1", 0), 10)
                it.soTimeout = 1_000
            } catch (failure: Throwable) {
                runCatching { it.close() }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }
        val port: Int get() = listener.localPort
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "smoke-$label").apply { isDaemon = true } }
        private val lock = Any()
        @Volatile private var closed = false
        private var active: Socket? = null
        private var worker: Future<*>? = null

        init {
            try {
                worker = executor.submit {
                    while (!closed) {
                        val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                        catch (failure: Exception) {
                            if (!closed) failures.add("$label accept " + failure.javaClass.simpleName)
                            break
                        }
                        socket.use {
                            synchronized(lock) { if (closed) return@use; active = socket }
                            var deadline: ScheduledFuture<*>? = null
                            try {
                                deadline = watchdog.schedule(Runnable { runCatching { socket.close() } }, 5, TimeUnit.SECONDS)
                                socket.soTimeout = 2_000
                                serve(socket)
                            } catch (failure: Exception) {
                                if (!closed) failures.add("$label protocol " + failure.javaClass.simpleName)
                            } finally {
                                deadline?.cancel(false)
                                synchronized(lock) { active = null }
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                runCatching { close() }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
        }

        override fun close() {
            val socket = synchronized(lock) { closed = true; active }
            closeAll(
                { listener.close() }, { socket?.close() },
                { executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "$label worker did not stop" } },
                { worker?.get(1, TimeUnit.SECONDS) },
            )
        }
    }

    companion object {
        private fun readLine(input: InputStream, limit: Int): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < limit) {
                val byte = input.read()
                check(byte >= 0) { "Unexpected socket EOF" }
                if (byte == 10) {
                    check(bytes.lastOrNull() == 13.toByte()) { "Expected CRLF" }
                    return bytes.dropLast(1).toByteArray().toString(Charsets.US_ASCII)
                }
                check(byte in 13..126) { "Invalid protocol byte" }
                bytes.add(byte.toByte())
            }
            error("Protocol line too long")
        }

        private fun closeAll(vararg actions: () -> Unit) {
            var first: Throwable? = null
            var interrupted = Thread.interrupted()
            actions.forEach { action ->
                try { action() } catch (failure: Throwable) {
                    if (failure is InterruptedException) interrupted = true
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            first?.let { throw it }
        }
    }
}
