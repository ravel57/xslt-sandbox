package ru.ravel.xsltsandbox.log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал в файл `XSLTSandbox.log` рядом с исполняемым файлом (для jpackage — в папке с `XSLTSandbox.exe`).
 * Если туда писать нельзя, файл создаётся в temp. Кроме явных записей, в журнал попадают всё, что
 * приложение пишет в System.out/System.err, и необработанные исключения любого потока.
 * Журнал никогда не бросает исключений: сбой записи не должен ломать приложение.
 */
object AppLog {

	private const val FILE_NAME = "XSLTSandbox.log"
	private const val MAX_BYTES = 5L * 1024 * 1024
	private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

	private val lock = Any()
	private var file: File? = null
	private var installed = false

	/** Путь к текущему файлу журнала; null, пока журнал не установлен или файл не удалось создать. */
	val path: File? get() = file

	/** Подключает журнал. Вызывается один раз в начале `main`. */
	fun install(args: Array<out String>) {
		synchronized(lock) {
			if (installed) return
			installed = true
			file = openLogFile()
		}
		interceptStreams()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			error("Необработанное исключение в потоке ${thread.name}", error)
		}
		info("==== запуск XSLT Sandbox ====")
		info("java ${System.getProperty("java.version")}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
		info("исполняемый файл: ${System.getProperty("jpackage.app-path") ?: "<не jpackage>"}")
		info("рабочая папка: ${System.getProperty("user.dir")}")
		info("аргументы: ${args.joinToString(" ") { if (' ' in it) "\"$it\"" else it }}")
		info("журнал: ${file?.absolutePath}")
		Runtime.getRuntime().addShutdownHook(Thread { info("==== завершение ====") })
	}

	fun info(message: String) = write("INFO ", message, null)

	fun warn(message: String, error: Throwable? = null) = write("WARN ", message, error)

	fun error(message: String, error: Throwable? = null) = write("ERROR", message, error)

	/** Папка рядом с exe: из `jpackage.app-path`, иначе папка jar, иначе рабочая. */
	internal fun candidateDirectories(): List<File> = listOfNotNull(
		System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile },
		runCatching { File(AppLog::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
			?.let { if (it.isFile) it.parentFile else it },
		File(System.getProperty("user.dir")),
		File(System.getProperty("java.io.tmpdir")),
	)

	private fun openLogFile(): File? {
		for (dir in candidateDirectories()) {
			val target = File(dir, FILE_NAME)
			try {
				if (!dir.isDirectory) continue
				rotate(target)
				target.appendText("", StandardCharsets.UTF_8)
				return target
			} catch (_: Exception) {
				// пробуем следующую папку
			}
		}
		return null
	}

	private fun rotate(target: File) {
		if (target.isFile && target.length() > MAX_BYTES) {
			val old = File(target.parentFile, "$FILE_NAME.1")
			old.delete()
			target.renameTo(old)
		}
	}

	private fun write(level: String, message: String, error: Throwable?) {
		val text = buildString {
			append(LocalDateTime.now().format(TIME)).append(' ').append(level).append(' ')
				.append('[').append(Thread.currentThread().name).append("] ").append(message)
			if (error != null) {
				append('\n').append(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd())
			}
			append('\n')
		}
		synchronized(lock) {
			val target = file ?: return
			try {
				target.appendText(text, StandardCharsets.UTF_8)
			} catch (_: Exception) {
				// журнал не должен ломать приложение
			}
		}
	}

	/** Всё, что печатают в System.out/err, продолжает идти в консоль и дублируется в файл. */
	private fun interceptStreams() {
		System.setOut(teeTo(System.out, "OUT  "))
		System.setErr(teeTo(System.err, "ERR  "))
	}

	private fun teeTo(original: PrintStream, level: String): PrintStream {
		val line = ByteArrayOutputStream()
		val stream = object : OutputStream() {
			override fun write(b: Int) {
				original.write(b)
				synchronized(line) {
					if (b == '\n'.code) flushLine() else line.write(b)
				}
			}

			override fun flush() = original.flush()

			private fun flushLine() {
				val text = line.toString(StandardCharsets.UTF_8).trimEnd('\r')
				line.reset()
				if (text.isNotEmpty()) write(level, text, null)
			}
		}
		return PrintStream(stream, true, StandardCharsets.UTF_8)
	}
}
