package app.ageha.core.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A real HTTP server that speaks the sync protocol.
 *
 * The JDK's own `com.sun.net.httpserver`, not MockWebServer, because it adds no dependency for
 * something this small -- and this project has already declined a native library over exactly that
 * kind of cost. It is a real socket either way, so the client under test goes through real OkHttp,
 * real headers and a real response body rather than an interception seam. A sync client tested
 * against a stubbed transport proves only that its own mock agrees with it.
 *
 * Handlers are per path and are supplied by each test, so a test states the server behaviour it is
 * about -- a 401 on the first call, a 204, a malformed reply -- instead of sharing one fixture
 * that has to anticipate all of them.
 */
class FakeSyncServer : AutoCloseable {

	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val handlers = mutableMapOf<String, (RecordedRequest) -> Canned>()

	/** Every request the server received, in order. */
	val requests = ConcurrentLinkedQueue<RecordedRequest>()

	val baseUrl: String get() = "http://127.0.0.1:" + server.address.port

	init {
		server.createContext("/") { exchange -> dispatch(exchange) }
		server.executor = null
		server.start()
	}

	/** Answer [path] with whatever [handler] decides, per request. */
	fun on(path: String, handler: (RecordedRequest) -> Canned) = apply {
		handlers[path] = handler
	}

	/** Answer [path] with the same thing every time. */
	fun on(path: String, status: Int, body: String) = on(path) { Canned(status, body) }

	private fun dispatch(exchange: HttpExchange) {
		val recorded = RecordedRequest(
			path = exchange.requestURI.path,
			body = exchange.requestBody.readBytes().decodeToString(),
			headers = exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") },
		)
		requests += recorded
		val canned = handlers[recorded.path]?.invoke(recorded)
			?: Canned(404, "\"no handler for " + recorded.path + "\"")
		val bytes = canned.body.toByteArray()
		exchange.responseHeaders.add("Content-Type", "application/json")
		// 204 must not carry a body, and the JDK server enforces that by throwing if one is
		// written. -1 is its idiom for "no response body".
		if (canned.status == 204 || bytes.isEmpty()) {
			exchange.sendResponseHeaders(canned.status, -1)
		} else {
			exchange.sendResponseHeaders(canned.status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		}
		exchange.close()
	}

	fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.path == path }

	override fun close() {
		server.stop(0)
	}

	data class RecordedRequest(
		val path: String,
		val body: String,
		val headers: Map<String, String>,
	) {
		fun header(name: String): String? = headers[name.lowercase()]
	}

	data class Canned(val status: Int, val body: String)
}
