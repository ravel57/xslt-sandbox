package ru.ravel.xsltsandbox.files

import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.util.concurrent.ConcurrentHashMap
import javafx.application.Platform
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.utils.XmlUtil

/**
 * Следит за изменениями открытых файлов и подтягивает их в редакторы.
 */
class FileWatcher(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
) {
	private val state get() = ctx.editor

	private val watchService = FileSystems.getDefault().newWatchService()
	private val watchMap = ConcurrentHashMap<Path, Pair<DocSession, CodeArea>>()
	private val watchDirs = mutableSetOf<Path>()

	@Volatile
	private var watcherRunning = true


	fun start() {
		Thread({
			try {
				while (watcherRunning) {
					val key = try {
						watchService.take()
					} catch (_: ClosedWatchServiceException) {
						break // выходим из цикла
					}
					val dir = key.watchable() as Path
					for (event in key.pollEvents()) {
						val ev = event as WatchEvent<Path>
						val changed = dir.resolve(ev.context())
						val entry = watchMap[changed] ?: continue
						val (session, area) = entry

						Platform.runLater {
							try {
								val file = changed.toFile()
								val text = XmlUtil.readXmlSafe(file)
								session.xsltEncoding = XmlUtil.getEncoding(file.readBytes())
								state.suspendHighlighting++
								try {
									area.replaceText(text)
								} finally {
									state.suspendHighlighting--
									support.highlightAllMatches(area, state.query, area === session.resultArea)
								}
							} catch (ex: Exception) {
								showStatus(ctx.stage, "Не удалось обновить файл:\n$changed\n${ex.message}")
							}
						}
					}
					key.reset()
				}
			} catch (ex: Exception) {
				if (watcherRunning) ex.printStackTrace()
			}
		}, "watch-thread").apply { isDaemon = true }.start()
	}


	fun register(path: Path, session: DocSession, area: CodeArea) {
		val dir = path.parent
		watchMap[path] = session to area
		if (watchDirs.add(dir)) {
			dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
		}
	}


	fun close() {
		watcherRunning = false
		watchService.close()
	}

}
