package ru.ravel.xsltsandbox.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProcessPathsTest {

	@TempDir
	lateinit var tmp: Path

	private fun process(name: String): Path =
		tmp.resolve(name).also { Files.createDirectories(it.resolve("Procedures/Main/DS_1")) }

	@Test
	fun `activity from a branch snapshot is mapped to the real process`() {
		val real = process("real")
		val snapshot = process("snapshot")

		val mapped = ProcessPaths.inRealProcess(real, snapshot.resolve("Procedures/Main/DS_1"))

		assertEquals(real.resolve("Procedures/Main/DS_1"), mapped)
	}

	@Test
	fun `main flow activity is mapped too`() {
		val real = tmp.resolve("real").also { Files.createDirectories(it.resolve("MainFlow")) }
		val snapshot = tmp.resolve("snap").also { Files.createDirectories(it.resolve("MainFlow/DS_2")) }

		assertEquals(real.resolve("MainFlow/DS_2"), ProcessPaths.inRealProcess(real, snapshot.resolve("MainFlow/DS_2")))
	}

	@Test
	fun `path already inside the real process or without a process stays as is`() {
		val real = process("real")
		val own = real.resolve("Procedures/Main/DS_1")

		assertEquals(own, ProcessPaths.inRealProcess(real, own))
		assertEquals(own, ProcessPaths.inRealProcess(null, own))
	}
}
