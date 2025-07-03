package ru.ravel.xsltsandbox.dto.editor

data class XPathMeta(
	val xpath: String,          // итоговый путь
	val segs: List<SegMeta>     // метаданные для GUI-редактора
)