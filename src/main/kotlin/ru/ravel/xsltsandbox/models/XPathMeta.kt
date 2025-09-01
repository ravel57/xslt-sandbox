package ru.ravel.xsltsandbox.models


data class XPathMeta(
	val xpath: String,         // итоговый путь
	val segs: List<SegMeta>   // метаданные для GUI-редактора
)