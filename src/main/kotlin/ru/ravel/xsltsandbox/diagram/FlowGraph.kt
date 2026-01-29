package ru.ravel.xsltsandbox.diagram

class FlowGraph(edges: List<DirEdge>) {
	val edges: List<DirEdge> = edges
	val outs: Map<String, List<DirEdge>> = edges.groupBy { it.fromUid }
}