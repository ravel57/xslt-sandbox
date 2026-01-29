package ru.ravel.xsltsandbox.diagram

data class DiagramLayout(
	val positions: Map<String, NodePos>,
	val width: Double,
	val height: Double
)