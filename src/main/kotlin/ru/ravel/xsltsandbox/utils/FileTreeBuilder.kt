package ru.ravel.xsltsandbox.utils

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javafx.scene.control.TreeItem

object FileTreeBuilder {
	/** Бросается, когда построение дерева отменено (например, пользователь продолжил ввод запроса) */
	class BuildCancelledException : RuntimeException(null, null, false, false)

	fun buildFileTree(path: Path, isCancelled: () -> Boolean = { false }): TreeItem<Path> {
		if (isCancelled()) throw BuildCancelledException()
		val root = TreeItem(path)
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			try {
				Files.newDirectoryStream(path).use { ds ->
					for (child in ds) {
						root.children.add(buildFileTree(child, isCancelled))
					}
				}
			} catch (e: BuildCancelledException) {
				throw e
			} catch (_: Exception) {
			}
		}
		return root
	}


	/** «Умное» сопоставление: символы запроса должны встречаться по порядку, возможны любые вставки между ними */
	private fun smartMatch(name: String, query: String): Boolean {
		var qi = 0
		for (ch in name) {
			if (qi < query.length && ch.equals(query[qi], ignoreCase = true)) qi++
		}
		return qi == query.length
	}


	/**
	 * Рекурсивно строит отфильтрованное дерево: оставляет узлы, которые сами матчатся, или содержат потомков, которые матчатся.
	 * Символические ссылки на папки не раскрываются (защита от циклов); [isCancelled] проверяется на каждом узле.
	 */
	fun buildFilteredFileTree(path: Path, query: String, isCancelled: () -> Boolean = { false }): TreeItem<Path> {
		val q = query.trim()

		fun isDir(p: Path) = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)

		fun listChildren(p: Path): List<Path> = try {
			Files.newDirectoryStream(p).use { ds -> ds.toList() }
		} catch (_: Exception) {
			emptyList()
		}

		fun filterRec(p: Path): TreeItem<Path>? {
			if (isCancelled()) throw BuildCancelledException()
			val dir = isDir(p)
			val selfMatches = smartMatch(p.fileName?.toString() ?: p.toString(), q)

			if (selfMatches) {
				// если папка сама совпала — показываем всё её содержимое
				return TreeItem(p).apply {
					if (dir) {
						listChildren(p).forEach { child ->
							children += TreeItem(child).apply {
								if (isDir(child)) {
									children.add(TreeItem<Path>()) // пустой потомок → чтобы можно было раскрывать
								}
							}
						}
					}
				}
			}
			if (!dir) return null

			// сначала папки, потом файлы, по имени
			val sorted = listChildren(p).sortedWith(
				compareBy<Path>({ !isDir(it) }, { it.fileName.toString().lowercase() })
			)
			val childrenItems = sorted.mapNotNull { filterRec(it) }
			return if (childrenItems.isNotEmpty()) TreeItem(p).apply { children.addAll(childrenItems) } else null
		}

		// Корневой элемент всегда присутствует (TreeView.isShowRoot=false, так что сам root не показывается)
		return TreeItem(path).apply {
			val rootFiltered = filterRec(path)
			children.setAll(rootFiltered?.children ?: emptyList())
		}
	}
}
