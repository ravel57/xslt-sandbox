package ru.ravel.xsltsandbox.models

data class SegMeta(
	val name: String,          // имя элемента
	val predicate: String,     // исходный индекс в виде "[n]"
	val attrs: Map<String, String>  // найденные атрибуты
)