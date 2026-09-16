package ru.ravel.xsltsandbox.models

import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TreeView
import javafx.scene.layout.VBox
import org.fxmisc.richtext.CodeArea
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import java.nio.charset.Charset
import java.nio.file.Path
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.stage.Stage

data class DocSession(
	val tab: Tab,
	var xsltArea: CodeArea,
	val xmlArea: CodeArea,
	val resultArea: CodeArea,
	val nanCountLabel: Label,
	var xmlPath: Path? = null,
	var brPath: Path? = null,
	var xsltPath: Path? = null,
	var otherActivityPath: Path? = null,
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
	var dataDocs: String? = null,
	var mappingPropertyFile: Path? = null,
) {

	val debugCurrentActivityProps = SimpleObjectProperty<Path?>(null)
	val debugLastExitName = SimpleStringProperty(null)
	var onDebugStep: (() -> Unit)? = null
	var onDebugRun: (() -> Unit)? = null
	var onOpenDataDocsViewer: ((Stage, String, String) -> Unit)? = null

	data class DebugEdgeKey(val from: String, val exit: String?, val to: String)
	data class DebugEdgeDocs(val inDocs: String, val outDocs: String)
	val debugDocsByEdge: MutableMap<DebugEdgeKey, DebugEdgeDocs> = LinkedHashMap()

	fun debugActivityNameOf(p: Path?): String? {
		if (p == null) return null
		return p.parent?.fileName?.toString()
	}


	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false
		other as DocSession
		return tab == other.tab
	}


	override fun hashCode(): Int {
		return tab.hashCode()
	}

}