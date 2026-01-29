package ru.ravel.xsltsandbox.diagram

data class DiagramNodeModel(
	val uid: String,
	val title: String,
	val x: Double,
	val y: Double,
	val isStart: Boolean,
	val isEnd: Boolean,
	val isCurrent: Boolean
)