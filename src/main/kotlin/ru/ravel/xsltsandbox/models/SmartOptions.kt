package ru.ravel.xsltsandbox.models


data class SmartOptions(
	val allowAttributeShortcut: Boolean = true, // @id — ок
	val allowDot: Boolean = false,              // .  — относит. (по умолчанию ругаемся)
	val allowDotDot: Boolean = false            // .. — относит. (по умолчанию ругаемся)
)