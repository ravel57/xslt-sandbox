package ru.ravel.xsltsandbox.diagram

class DirEdge(
	val fromUid: String,
	val toUid: String,
	val exitName: String?
) : GEdge(fromUid, toUid, exitName)