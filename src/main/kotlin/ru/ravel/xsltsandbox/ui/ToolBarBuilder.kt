package ru.ravel.xsltsandbox.ui

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.inputDocuments
import ru.ravel.xsltsandbox.log.AppLog
import ru.ravel.xsltsandbox.debugger.ActivityDebugger
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.editor.SearchDialog
import ru.ravel.xsltsandbox.files.SessionFiles
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.transform.Transformer
import ru.ravel.xsltsandbox.utils.XmlUtil
import ru.ravel.xsltsandbox.xml.XPathTools
import java.io.File

/**
 * Верхняя панель инструментов.
 */
class ToolBarBuilder(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
	private val search: SearchDialog,
	private val xpath: XPathTools,
	private val files: SessionFiles,
	private val transformer: Transformer,
	private val debugger: ActivityDebugger,
) {
	private val state get() = ctx.editor

	/** Пункт «Select file» меню DataDocs: по нему же дата-доки можно загрузить без диалога (см. [selectDataDocsFile]). */
	private var selectDataDocsFileItem: MenuItem? = null
	private var pendingDataDocsFile: File? = null

	/** Загружает файл в DataDocs текущей сессии и подставляет в XML входные документы активности. */
	private fun loadDataDocsFile(file: File) {
		ctx.currentSession.dataDocs = XmlUtil.readXmlSafe(file)
		val properties = ctx.currentSession.mappingPropertyFile?.toFile()
		if (properties != null) {
			val input = inputDocuments(properties, ctx.currentSession.dataDocs!!)
			AppLog.info("датадоки из ${file.name} (${ctx.currentSession.dataDocs!!.length} симв.) → вход ${properties.name}: ${input.length} симв.")
			ctx.currentSession.xmlArea.replaceText(input)
		}
	}

	/**
	 * То же, что выбрать [file] в «DataDocs → Select file», только без диалога: загрузка, подстановка
	 * входных документов и обновление кнопок отладчика (их обновляют обработчики пунктов меню).
	 * Нужно для аргумента запуска `--input-full-datadocs-path`.
	 */
	fun selectDataDocsFile(file: File) {
		val item = selectDataDocsFileItem ?: return
		pendingDataDocsFile = file
		item.fire()
	}

	fun build(): HBox {
		val validateAntTransformBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.CHECK_CIRCLE)
			tooltip = Tooltip("Validate & Transform")
			setOnAction { transformer.doTransform(ctx.stage) }
		}
		val searchBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.SEARCH)
			tooltip = Tooltip("Search")
			setOnAction { search.showSearchWindow(ctx.stage, ctx.currentSession.currentAreaOr(xml = true)) }
		}

		val xpathMenu = MenuButton("XPath…").apply {
			items.addAll(
				MenuItem("Edit XPath…").apply {
					setOnAction { xpath.openXPathEditor(ctx.stage) }
				},
				MenuItem("Execute XPath…").apply {
					setOnAction { xpath.executeXpath(ctx.stage) }
				}
			)
		}

		val openXmlBtn = Button("Open XML…").apply {
			setOnAction {
				val file = FileChoosers.create("Open XML…", ctx.currentSession.xmlPath, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(ctx.stage) ?: return@setOnAction
				files.openXmlFile(ctx.currentSession, file.toPath())
			}
		}
		val openXsltBtn = Button("Open XSLT…").apply {
			setOnAction {
				val file = FileChoosers.create(
					"Open XSLT…",
					ctx.currentSession.xsltPath,
					"XSLT Files (*.xsl, *.xslt)",
					"*.xsl",
					"*.xslt"
				)
					.showOpenDialog(ctx.stage) ?: return@setOnAction
				files.openXsltFile(ctx.currentSession, file.toPath())
			}
		}
		val openBrBtn = Button("Open BR…").apply {
			isVisible = false
			isManaged = false
			setOnAction {
				val file = FileChoosers.create("Open BR…", ctx.currentSession.xsltPath, "XML Files (*.xml)", "*.xml")
					.showOpenDialog(ctx.stage) ?: return@setOnAction
				files.openBrFile(ctx.currentSession, file.toPath())
			}
		}
		val openStack = StackPane(openXsltBtn, openBrBtn).apply {
			minWidth = Region.USE_PREF_SIZE
			prefWidth = 100.0
		}
		HBox.setMargin(openXsltBtn, Insets(0.0, 0.0, 0.0, 16.0))

		val saveXsltBtn = Button("Save XSLT…").apply {
			setOnAction { files.saveCurrentXslt() }
		}

		val saveStack = StackPane(saveXsltBtn).apply {
			minWidth = Region.USE_PREF_SIZE
			prefWidth = 100.0
		}

		ctx.xsltRadio = RadioButton("XSLT").apply {
			isSelected = true
		}
		ctx.brRadio = RadioButton("BR")
		val modeGroup = ToggleGroup().apply {
			ctx.xsltRadio.toggleGroup = this
			ctx.brRadio.toggleGroup = this
		}
		modeGroup.selectedToggleProperty().addListener { _, _, new ->
			when (new) {
				ctx.xsltRadio -> {
					ctx.currentSession.mode = TransformMode.XSLT
					openXsltBtn.isVisible = true
					openXsltBtn.isManaged = true
					openBrBtn.isVisible = false
					openBrBtn.isManaged = false
					saveXsltBtn.isVisible = true
					saveXsltBtn.isManaged = true

					ctx.currentSession.xsltBox?.isVisible = true
					ctx.currentSession.xsltBox?.isManaged = true
					ctx.currentSession.brBox?.isVisible = false
					ctx.currentSession.brBox?.isManaged = false
				}

				ctx.brRadio -> {
					ctx.currentSession.mode = TransformMode.BR
					openXsltBtn.isVisible = false
					openXsltBtn.isManaged = false
					openBrBtn.isVisible = true
					openBrBtn.isManaged = true
					saveXsltBtn.isVisible = false
					saveXsltBtn.isManaged = false

					ctx.currentSession.xsltBox?.isVisible = false
					ctx.currentSession.xsltBox?.isManaged = false
					ctx.currentSession.brBox?.isVisible = true
					ctx.currentSession.brBox?.isManaged = true
				}
			}
		}

		val disableHighlightCheck = CheckBox("Disable syntactic highlights").apply {
			isSelected = state.disableSyntaxHighlighting
			setOnAction {
				state.disableSyntaxHighlighting = isSelected
				ctx.sessions.values.forEach { s ->
					listOf(s.xsltArea, s.xmlArea, s.resultArea).forEach {
						support.highlightAllMatches(it, state.query, it === s.resultArea)
					}
				}
			}
		}
		HBox.setMargin(disableHighlightCheck, Insets(0.0, 0.0, 0.0, 16.0))

		val activitySeparator = Separator(Orientation.VERTICAL)
		val activityLabel = Label("Activities debugger:")
		val nextActivityBtn = Button().apply {
			graphic = FontIcon(FontAwesomeSolid.ARROW_RIGHT)
			tooltip = Tooltip("Next layout activity")
			setOnAction { debugger.goToNextActivity() }
		}

		val runDebugBtn = Button("Run debug").apply {
			tooltip = Tooltip("Run debugger through all possible routes (requires DataDocs)")
			setOnAction { debugger.runDebugAllPaths() }
		}

		val dataDocsActivityBtn = MenuButton().apply {
			graphic = FontIcon(FontAwesomeSolid.FILE_CODE)
			tooltip = Tooltip("Open DataDocs")

			val manualItem = MenuItem("Input text").apply {
				setOnAction {
					val dlg = Stage().apply {
						initOwner(ctx.stage)
						initModality(Modality.WINDOW_MODAL)
						title = "DataDocs Editor"
					}
					val area = support.createHighlightingCodeArea(false).apply {
						prefWidth = 600.0
						prefHeight = 400.0
						replaceText(ctx.currentSession.dataDocs ?: "")
					}
					val okBtn = Button("OK").apply {
						setOnAction {
							ctx.currentSession.dataDocs = area.text
							val properties = ctx.currentSession.mappingPropertyFile?.toFile()
							if (properties != null) {
								ctx.currentSession.xmlArea.replaceText(inputDocuments(properties, ctx.currentSession.dataDocs!!))
							}
							dlg.close()
						}
					}
					dlg.scene = Scene(VBox(8.0, area, okBtn).apply { padding = Insets(10.0) })
					dlg.show()
				}
			}

			val fileItem = MenuItem("Select file").apply {
				setOnAction {
					// Файл уже известен, если пункт вызвали из selectDataDocsFile — тогда диалог не нужен.
					val file = pendingDataDocsFile?.also { pendingDataDocsFile = null }
						?: FileChoosers.create("Open DataDocs", null, "XML Files (*.xml)", "*.xml")
							.showOpenDialog(ctx.stage)
						?: return@setOnAction
					loadDataDocsFile(file)
				}
			}
			selectDataDocsFileItem = fileItem

			val exportDataDocs = MenuItem("Export to file").apply {
				setOnAction {
					val file = FileChoosers.create("Export DataDocs", null, "XML Files (*.xml)", "*.xml")
						.showSaveDialog(ctx.stage)
						?: return@setOnAction
					file.writeText(ctx.currentSession.dataDocs ?: "")
				}
			}

			items.addAll(manualItem, fileItem, SeparatorMenuItem(), exportDataDocs)
		}

		val diagramSeparator = Separator(Orientation.VERTICAL)
//		val diagramBtn = Button().apply {
//			graphic = FontIcon(FontAwesomeSolid.SITEMAP)
//			tooltip = Tooltip("Show flow diagram")
//			setOnAction { RouteFinder(ctx.stage, ctx.currentSession).openFlowDiagramWindow() }
//		}

		fun updateActivityButtons() {
			val xsltLoaded = ctx.currentSession.xsltPath != null
			val brLoaded = ctx.currentSession.brRoot != null || ctx.currentSession.brRootQuant != null
			nextActivityBtn.isDisable = !(xsltLoaded || brLoaded)
			runDebugBtn.isDisable = ctx.currentSession.dataDocs.isNullOrBlank() || !(xsltLoaded || brLoaded)
		}
		ctx.refreshActivityButtons = { updateActivityButtons() }

		dataDocsActivityBtn.items.forEach { item ->
			item.addEventHandler(ActionEvent.ACTION) {
				updateActivityButtons()
			}
		}

		updateActivityButtons()
		modeGroup.selectedToggleProperty().addListener { _, _, _ ->
			updateActivityButtons()
		}

		return HBox(
			8.0,
			validateAntTransformBtn,
			searchBtn,
			xpathMenu,
			ctx.xsltRadio,
			ctx.brRadio,
			openStack,
			saveStack,
			openXmlBtn,
			disableHighlightCheck,
			activitySeparator,
			activityLabel,
			nextActivityBtn,
			runDebugBtn,
			dataDocsActivityBtn,
			diagramSeparator,
//			diagramBtn,
		).apply {
			alignment = Pos.CENTER_LEFT
			padding = Insets(10.0)
		}
	}


	private fun DocSession.currentAreaOr(xml: Boolean): CodeArea {
		return ctx.currentArea ?: if (xml) this.xmlArea else this.xsltArea
	}
}
