package ru.ravel.xsltsandbox.diagram

data class Route(
	val nodes: List<String>,
	val edges: List<GEdge>
)