package ru.ravel.xsltsandbox.models


data class ValueOfWarning(
	val range: IntRange, // диапазон в XSLT тексте — для подчёркивания
	val line: Int,       // 1-based
	val col: Int,        // 1-based
	val raw: String      // исходное значение @select
)