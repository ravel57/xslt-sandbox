package ru.ravel.xsltsandbox.dto.editor

data class AppConfig(
	val xml: String? = null,
	val xslt: String? = null,
	val xmlDir: String? = null,
	val xsltDir: String? = null
)