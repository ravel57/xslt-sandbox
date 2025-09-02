package ru.ravel.xsltsandbox.models

import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TreeView
import javafx.scene.layout.VBox
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.models.br.Connective
import ru.ravel.xsltsandbox.models.br.Quantifier
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
	var xmlEncoding: Charset? = null,
	var brTree: TreeView<String>? = null,
	var xsltBox: VBox? = null,
	var brBox: VBox? = null,
	var mode: TransformMode = TransformMode.XSLT,
	var brRoot: Connective? = null,
	var brRootQuant: Quantifier? = null,
)