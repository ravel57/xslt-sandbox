package ru.ravel.xsltsandbox.models

import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.control.Tab
import org.fxmisc.richtext.CodeArea
import java.nio.charset.Charset
import java.nio.file.Path


data class DocSession(
	val tab: Tab,
	var xsltArea: CodeArea,
	val xmlArea: CodeArea,
	val resultArea: CodeArea,
	val nanCountLabel: Label,
	var xmlPath: Path? = null,
	var xsltPath: Path? = null,
	var xsltSyntaxErrorRanges: List<IntRange> = emptyList(),
	var xsltBadSelectRanges: List<IntRange> = emptyList(),
	var xsltWarningRanges: List<IntRange> = emptyList(),
	var xsltOverlay: Canvas? = null,
	var xsltStatusLabel: Label? = null,
	var xsltEncoding: Charset? = null,
	var xmlEncoding: Charset? = null
)