package ru.ravel.xsltsandbox.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * Process viewer может открыть активность из временного снимка ветки git, а не из самого процесса.
 * Файлы, которые sandbox создаёт сам (Mock.xml), должны попадать в реальный процесс.
 */
object ProcessPaths {

	private val ROOT_MARKERS = listOf("MainFlow", "Procedures")

	/** Корень процесса, в котором лежит [path]: ближайшая папка выше с MainFlow или Procedures. */
	fun rootOf(path: Path): Path? {
		return generateSequence(path.toAbsolutePath().normalize()) { it.parent }
			.firstOrNull { dir -> ROOT_MARKERS.any { Files.isDirectory(dir.resolve(it)) } }
	}

	/** Папка бизнес-правил (`BusinessRules`) процесса, которому принадлежит [path]. */
	fun businessRulesDir(path: Path): Path? {
		return rootOf(path)?.resolve("BusinessRules")
	}

	/**
	 * Путь [path] внутри реального процесса [processRoot]: если [path] уже в нём (или процесс не задан) —
	 * он же, иначе относительный путь от корня процесса, в котором лежит [path], пересаживается на [processRoot].
	 */
	fun inRealProcess(processRoot: Path?, path: Path): Path {
		if (processRoot == null) return path
		val real = processRoot.toAbsolutePath().normalize()
		val source = path.toAbsolutePath().normalize()
		if (source.startsWith(real)) return path
		val sourceRoot = rootOf(source) ?: return path
		return real.resolve(sourceRoot.relativize(source).toString())
	}
}
