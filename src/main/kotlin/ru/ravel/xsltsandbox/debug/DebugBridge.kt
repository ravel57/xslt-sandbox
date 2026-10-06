package ru.ravel.xsltsandbox.debug

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Один выполненный шаг отладчика — то, что уходит в process viewer. */
data class DebugStep(
	/** Папка процедуры: MainFlow или имя из Procedures. */
	val procedure: String,
	/** Активность, которую только что выполнили (имя её папки). */
	val activity: String,
	val mode: String,
	val exit: String?,
	/** Активность, на которую перешёл отладчик; null — шаг был последним. */
	val next: String?,
	/** Дата-документы, поданные на вход активности. */
	val docsIn: String?,
	/** Результат активности (для XSLT — выходные дата-документы). */
	val docsOut: String?,
)

/**
 * Передаёт шаги отладчика в process viewer по TCP на loopback: одна JSON-строка на шаг.
 * Порт приходит аргументом запуска `--debug-port`. Отправка идёт в фоне и никогда не блокирует
 * интерфейс: если viewer закрыт, шаг молча пропускается, а при следующем шаге соединение
 * пробуется снова.
 */
class DebugBridge(private val port: Int) : AutoCloseable {

	private val mapper = ObjectMapper()
	private val executor = Executors.newSingleThreadExecutor { task ->
		Thread(task, "debug-bridge").apply { isDaemon = true }
	}
	private var socket: Socket? = null
	private var writer: BufferedWriter? = null

	fun sendStep(step: DebugStep) {
		val message = mapper.writeValueAsString(
			linkedMapOf(
				"type" to "step",
				"procedure" to step.procedure,
				"activity" to step.activity,
				"mode" to step.mode,
				"exit" to step.exit,
				"next" to step.next,
				"docsIn" to step.docsIn,
				"docsOut" to step.docsOut,
			),
		)
		executor.execute { deliver(message) }
	}

	private fun deliver(line: String) {
		repeat(2) {
			try {
				val out = writer ?: connect()
				out.write(line)
				out.write("\n")
				out.flush()
				return
			} catch (_: IOException) {
				disconnect()
			}
		}
	}

	private fun connect(): BufferedWriter {
		val connection = Socket()
		connection.connect(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), CONNECT_TIMEOUT_MS)
		connection.tcpNoDelay = true
		socket = connection
		return BufferedWriter(OutputStreamWriter(connection.getOutputStream(), Charsets.UTF_8)).also { writer = it }
	}

	private fun disconnect() {
		runCatching { socket?.close() }
		socket = null
		writer = null
	}

	override fun close() {
		executor.execute { disconnect() }
		executor.shutdown()
	}

	private companion object {
		const val LOOPBACK = "127.0.0.1"
		const val CONNECT_TIMEOUT_MS = 500
	}
}
