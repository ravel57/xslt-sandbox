package ru.ravel.xsltsandbox.diagram

data class DiagramModel(
	val nodes: List<DiagramNodeModel>,
	val edges: List<DiagramEdgeModel>,
	val width: Double,
	val height: Double
)