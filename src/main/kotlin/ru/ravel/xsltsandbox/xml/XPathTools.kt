package ru.ravel.xsltsandbox.xml

import java.io.StringWriter
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import kotlin.io.path.name
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.Serializer
import net.sf.saxon.s9api.XdmNode
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.models.SegMeta
import ru.ravel.xsltsandbox.models.bizrule.XPath
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.xml.XPathBuilder.buildXPathWithMeta
import ru.ravel.xsltsandbox.xml.XPathSupport.buildDocForXPath
import ru.ravel.xsltsandbox.xml.XPathSupport.setDefaultNsFromDoc

/**
 * Построение и выполнение XPath по XML и результату.
 */
class XPathTools(private val ctx: AppContext) {
	private var editorDialog: Stage? = null


	fun openXPathEditor(owner: Stage) {
		val area = ctx.currentArea ?: return
		if (area !== ctx.currentSession.xmlArea && area !== ctx.currentSession.resultArea) {
			return
		}
		val meta = buildXPathWithMeta(area.text, area.selection.start)
		if (meta.xpath.isBlank()) {
			showStatus(owner, "Failed to build XPath")
			return
		}
		editorDialog?.let { dlg ->
			(dlg.scene.lookup("#xpathField") as TextField).text = meta.xpath
			dlg.toFront()
			dlg.requestFocus()
			return
		}
		/* ─────────────── GUI ─────────────── */
		/** одна строка «имя + ChoiceBox» */
		fun segRow(seg: SegMeta): HBox {
			val lbl = Label(seg.name)
			val cb = ChoiceBox<String>()
			val opts = mutableListOf<String>()
			if (seg.predicate.isNotEmpty()) opts.add("by index ${seg.predicate}")
			opts.add("no predicate")
			seg.attrs.forEach { (k, v) -> opts.add("@$k='$v'") }
			cb.items.addAll(opts)
			cb.value = opts[0]
			return HBox(6.0, lbl, cb)
		}

		val rows = meta.segs.map(::segRow)
		val rowsBox = VBox(4.0, *rows.toTypedArray())
		val rowsScroll = ScrollPane(rowsBox).apply {
			isFitToWidth = true
			hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
		}
		VBox.setVgrow(rowsScroll, Priority.ALWAYS)

		val resultField = TextField(meta.xpath).apply {
			id = "xpathField"
			isEditable = true
		}

		fun rebuild() {
			val sb = StringBuilder()
			rows.forEachIndexed { i, row ->
				val name = (row.children[0] as Label).text
				val sel = (row.children[1] as ChoiceBox<*>).value as String
				sb.append('/').append(name)
				when {
					sel.startsWith("@") -> sb.append("[$sel]")
					sel.startsWith("by index") -> sb.append(meta.segs[i].predicate)
				}
			}
			resultField.text = sb.toString()
		}
		rows.forEach { r ->
			(r.children[1] as ChoiceBox<*>).valueProperty()
				.addListener { _, _, _ -> rebuild() }
		}
		val okBtn = Button("Copy & Close")
		val dlg = Stage()
		editorDialog = dlg
		okBtn.setOnAction {
			val clip = Clipboard.getSystemClipboard()
			clip.setContent(ClipboardContent().apply {
				putString(resultField.text)
			})
			dlg.close()
		}
		dlg.apply {
			initOwner(owner)
			initModality(Modality.WINDOW_MODAL)
			title = "XPath editor"
			scene = Scene(VBox(8.0, rowsScroll, resultField, okBtn).apply {
				padding = Insets(12.0)
			})
			/* Esc — закрыть */
			scene.setOnKeyPressed { ev ->
				if (ev.code == KeyCode.ESCAPE) {
					close()
				}
			}
			setOnHidden { editorDialog = null }
			sizeToScene()
			show()
		}
	}


	fun executeXpath(primaryStage: Stage) {
		// ───────── создание диалога ─────────
		val dlg = Stage().apply {
			initOwner(primaryStage)
			initModality(Modality.WINDOW_MODAL)
			title = "Execute XPath"
		}

		val xpathField = TextField().apply { promptText = "Input XPath…" }

		val xmlRadio = RadioButton("XML")
		val resultRadio = RadioButton("Result")
		val tg = ToggleGroup().also { g ->
			xmlRadio.toggleGroup = g
			resultRadio.toggleGroup = g
		}
		if (ctx.currentArea === ctx.currentSession.resultArea) {
			tg.selectToggle(resultRadio)
		} else {
			tg.selectToggle(xmlRadio)
		}

		val runBtn = Button("Run")
		val closeBtn = Button("Close")
		// ───────── выполнение XPath ─────────
		runBtn.setOnAction {
			val xmlText = if (resultRadio.isSelected) {
				ctx.currentSession.resultArea.text
			} else {
				ctx.currentSession.xmlArea.text
			}
			val expr = xpathField.text.trim()
			if (expr.isEmpty()) {
				showStatus(primaryStage, "Empty expression.")
				return@setOnAction
			}
			try {
				val proc = Processor(false)
				val doc = buildDocForXPath(proc, xmlText)
				val compiler = proc.newXPathCompiler()
				setDefaultNsFromDoc(compiler, doc)
				val selector = compiler.compile(expr).load()
				selector.contextItem = doc
				val result = selector.evaluate()

				val out = buildString {
					for (item in result) {
						when (item) {
							is XdmNode -> {
								val sw = StringWriter()
								val serializer = proc.newSerializer(sw).apply {
									setOutputProperty(Serializer.Property.METHOD, "xml")
									setOutputProperty(Serializer.Property.INDENT, "yes")
									setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes")
								}
								serializer.serializeNode(item)
								append(sw.toString().trim()).append("\n\n")
							}

							else -> append(item.stringValue).append('\n')
						}

					}
				}.ifBlank { "— no results —" }

				showStatus(primaryStage, out)
				dlg.close()
			} catch (ex: Exception) {
				showStatus(primaryStage, "Error XPath:\n${ex.message}")
			}
		}
		closeBtn.setOnAction { dlg.close() }

		val root = VBox(
			10.0,
			xpathField,
			HBox(10.0, xmlRadio, resultRadio),
			HBox(10.0, runBtn, closeBtn)
		).apply { padding = Insets(12.0) }

		dlg.scene = Scene(root).also { sc ->
			sc.setOnKeyPressed {
				if (it.code == KeyCode.ESCAPE) {
					dlg.close()
				}
			}
		}
		dlg.show()
	}
}
