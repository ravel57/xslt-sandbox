package ru.ravel.xsltsandbox.models


data class AppConfig(
	val tabs: List<TabState> = emptyList(),
	val activeIndex: Int = 0
)