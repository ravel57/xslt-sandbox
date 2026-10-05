package ru.ravel.xsltsandbox.editor

/**
 * Настройки редактора, общие для всех [org.fxmisc.richtext.CodeArea] приложения.
 */
class EditorState {
	/** Текущий поисковый запрос, подсвечиваемый во всех областях */
	var query: String = ""
	var disableSyntaxHighlighting = false

	/** Пока больше нуля, перерисовка подсветки при изменении текста подавляется */
	@Volatile
	var suspendHighlighting = 0
}
