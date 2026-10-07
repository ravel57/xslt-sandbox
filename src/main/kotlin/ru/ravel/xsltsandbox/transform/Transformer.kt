package ru.ravel.xsltsandbox.transform

import java.io.StringReader
import java.io.StringWriter
import javafx.application.Platform
import javafx.stage.Stage
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.ErrorListener
import javax.xml.transform.Templates
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import kotlin.io.path.exists
import net.sf.saxon.s9api.Processor
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import ru.ravel.xsltsandbox.AppContext
import ru.ravel.xsltsandbox.br.BizRuleEvaluator
import ru.ravel.xsltsandbox.br.RuleParser
import ru.ravel.xsltsandbox.br.BizRuleEvaluator.evalQuantifier
import ru.ravel.xsltsandbox.br.BizRuleEvaluator.evaluateBR
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.applySetValues
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.extractNeededDataDocs
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor.getDataDocsInOut
import ru.ravel.xsltsandbox.editor.CodeAreaSupport
import ru.ravel.xsltsandbox.editor.XsltOverlay.redrawXsltOverlay
import ru.ravel.xsltsandbox.models.DocSession
import ru.ravel.xsltsandbox.models.Sev
import ru.ravel.xsltsandbox.models.SmartOptions
import ru.ravel.xsltsandbox.models.TransformMode
import ru.ravel.xsltsandbox.models.bizrule.Connective
import ru.ravel.xsltsandbox.models.bizrule.Quantifier
import ru.ravel.xsltsandbox.models.form.Form
import ru.ravel.xsltsandbox.models.procedurereturn.ProcedureReturn
import ru.ravel.xsltsandbox.models.segmentationtree.BusinessRule
import ru.ravel.xsltsandbox.models.segmentationtree.SegmentationTree
import ru.ravel.xsltsandbox.datadocs.DataDocsProcessor
import ru.ravel.xsltsandbox.log.AppLog
import ru.ravel.xsltsandbox.utils.ProcessPaths
import ru.ravel.xsltsandbox.models.wait.Wait
import ru.ravel.xsltsandbox.ui.Dialogs.showChoiceDialog
import ru.ravel.xsltsandbox.ui.Dialogs.showStatus
import ru.ravel.xsltsandbox.xml.XPathSupport.buildDocForXPath
import ru.ravel.xsltsandbox.xml.XPathSupport.setDefaultNsFromDoc
import ru.ravel.xsltsandbox.xslt.XsltErrorLocator.computeXsltErrorRange
import ru.ravel.xsltsandbox.xslt.XsltSelectChecker.collectBadValueOfSelectsSmart

/**
 * Проверка и выполнение XSLT, бизнес-правил и других активностей над текущей сессией.
 */
class Transformer(
	private val ctx: AppContext,
	private val support: CodeAreaSupport,
) {
	private val state get() = ctx.editor

	/**
	 * Performs XML well-formed check, XSLT compilation & transformation
	 *
	 * 	Работает с [ctx.currentSession]
	 *
	 */
	fun doTransform(owner: Stage): String {
		val s = ctx.currentSession

		when (s.mode) {
			TransformMode.BR -> {
				try {
					val xml = ctx.currentSession.xmlArea.text
					val br = ctx.currentSession.brRoot ?: ctx.currentSession.brRootQuant
					val result = when (br) {
						is Connective -> {
							evaluateBR(xml, br)
						}

						is Quantifier -> {
							val proc = Processor(false)
							val doc = buildDocForXPath(proc, xml)
							val compiler = proc.newXPathCompiler()
							setDefaultNsFromDoc(compiler, doc)
							evalQuantifier(br, compiler, doc)
						}

						else -> {
							false
						}
					}
					Platform.runLater {
						s.resultArea.replaceText(result.toString())
						support.highlightAllMatches(s.resultArea, state.query, true)
						s.nanCountLabel.text = ""
						s.nanCountLabel.isVisible = false
					}
					return result.toString()
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.XSLT -> {
				val status = StringBuilder()
				val saxonWarnAcc = mutableListOf<IntRange>()
				val saxonErrAcc = mutableListOf<IntRange>()
				val saxonFatalAcc = mutableListOf<IntRange>()

				try {
					SAXParserFactory.newInstance().apply {
						isNamespaceAware = true
						isValidating = false
					}.newSAXParser().parse(
						InputSource(StringReader(s.xmlArea.text)),
						object : DefaultHandler() {
							override fun warning(e: SAXParseException) {
								status.append("WARNING in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
							}

							override fun error(e: SAXParseException) {
								status.append("ERROR   in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
							}

							override fun fatalError(e: SAXParseException) {
								status.append("FATAL   in XML [line=${e.lineNumber},col=${e.columnNumber}]: ${e.message}\n")
							}
						}
					)
				} catch (ex: Exception) {
					status.append("XML parsing halted: ${ex.message}\n")
				}

				val tfFactory: TransformerFactory = TransformerFactory.newInstance(
					"net.sf.saxon.TransformerFactoryImpl",
					Transformer::class.java.classLoader
				).apply {
					errorListener = object : ErrorListener {
						override fun warning(ex: TransformerException) = report(Sev.WARNING, ex)
						override fun error(ex: TransformerException) = report(Sev.ERROR, ex)
						override fun fatalError(ex: TransformerException) = report(Sev.FATAL, ex)

						private fun report(sev: Sev, ex: TransformerException) {
							val loc = ex.locator
							val levelText = when (sev) {
								Sev.WARNING -> "WARNING in XSLT"
								Sev.ERROR -> "ERROR   in XSLT"
								Sev.FATAL -> "FATAL   in XSLT"
							}
							if (loc != null) {
								status.append("$levelText [line=${loc.lineNumber},col=${loc.columnNumber}]: ${ex.message}\n")
								val r = computeXsltErrorRange(s.xsltArea.text, loc.lineNumber, loc.columnNumber)
								when (sev) {
									Sev.WARNING -> saxonWarnAcc += r
									Sev.ERROR -> saxonErrAcc += r
									Sev.FATAL -> saxonFatalAcc += r
								}
							} else {
								status.append("$levelText: ${ex.message}\n")
							}
						}
					}
				}

				val xsltText = s.xsltArea.text
				val compatText = XsltCompat.asBackwardsCompatible(xsltText)
				var compat = false

				fun resetDiagnostics() {
					saxonWarnAcc.clear()
					saxonErrAcc.clear()
					saxonFatalAcc.clear()
				}

				fun modeNote() = if (compat) " • режим XSLT 1.0" else ""

				fun compile(text: String): Templates = tfFactory.newTemplates(StreamSource(StringReader(text))).also {
					status.append(if (compat) "XSLT compiled successfully (режим совместимости с XSLT 1.0).\n" else "XSLT compiled successfully.\n")
					s.xsltSyntaxErrorRanges = saxonErrAcc + saxonFatalAcc
					support.highlightAllMatches(s.xsltArea, state.query, false)
					appendBadSelectWarnings(s, status)
					saxonWarnAcc.addAll(s.xsltBadSelectRanges)
					s.xsltWarningRanges = saxonWarnAcc
					val note = modeNote()
					Platform.runLater {
						val errs = s.xsltSyntaxErrorRanges.size
						val warns = s.xsltWarningRanges.size
						s.xsltStatusLabel?.text = "Errors: $errs, Warnings: $warns$note"
						s.xsltStatusLabel?.isVisible = (errs + warns) > 0 || note.isNotEmpty()
					}
				}

				fun reportCompileFailure(ex: TransformerException) {
					s.xsltSyntaxErrorRanges = saxonErrAcc + saxonFatalAcc
					s.xsltWarningRanges = saxonWarnAcc + s.xsltBadSelectRanges
					support.highlightAllMatches(s.xsltArea, state.query, false)
					appendBadSelectWarnings(s, status)
					Platform.runLater {
						val errs = s.xsltSyntaxErrorRanges.size
						val warns = s.xsltWarningRanges.size
						s.xsltStatusLabel?.text = buildString {
							if (errs > 0) append("Errors: $errs")
							if (warns > 0) {
								if (errs > 0) append(", ")
								append("Warnings: $warns")
							}
						}
						s.xsltStatusLabel?.isVisible = (errs + warns) > 0
						redrawXsltOverlay(s)
						showStatus(owner, status.toString())
					}
				}

				fun fallBackToCompat(reason: String) {
					AppLog.info("XSLT: $reason — повтор в режиме совместимости с XSLT 1.0 (как у CRIF)")
					status.append("NOTE: $reason — повтор в режиме совместимости с XSLT 1.0 (как в CRIF).\n")
					resetDiagnostics()
					compat = true
				}

				var templates = try {
					compile(xsltText)
				} catch (ex: TransformerException) {
					if (compatText == null) {
						reportCompileFailure(ex)
						throw ex
					}
					fallBackToCompat("строгая компиляция XSLT 2.0 не прошла (${ex.message})")
					try {
						compile(compatText)
					} catch (ex2: TransformerException) {
						reportCompileFailure(ex2)
						throw ex2
					}
				}

				val writer = StringWriter()
				fun transformOnce(t: Templates): TransformerException? = try {
					t.newTransformer().apply {
						errorListener = tfFactory.errorListener
					}.transform(
						StreamSource(StringReader(s.xmlArea.text)),
						StreamResult(writer)
					)
					null
				} catch (ex: TransformerException) {
					ex
				}

				var failure = transformOnce(templates)
				if (failure != null && !compat && compatText != null) {
					fallBackToCompat("ошибка выполнения XSLT 2.0 (${failure.message})")
					writer.buffer.setLength(0)
					templates = try {
						compile(compatText)
					} catch (ex2: TransformerException) {
						reportCompileFailure(ex2)
						throw ex2
					}
					failure = transformOnce(templates)
				}
				failure?.let { System.err.println(it.localizedMessage) }

				val resultText = writer.toString()
				val note = modeNote()
				Platform.runLater {
					s.resultArea.replaceText(resultText)
					support.highlightAllMatches(s.resultArea, state.query, true)
					val nanCount = Regex("\\bNaN\\b").findAll(resultText).count()
					s.nanCountLabel.text = "NaNs: $nanCount"
					s.nanCountLabel.isVisible = nanCount > 0
					val errs = s.xsltSyntaxErrorRanges.size
					val warns = s.xsltWarningRanges.size
					s.xsltStatusLabel?.text = buildString {
						if (errs > 0) {
							append("Errors: $errs")
						}
						if (warns > 0) {
							if (errs > 0) {
								append(", ")
							}
							append("Warnings: $warns")
						}
						append(note)
					}
					s.xsltStatusLabel?.isVisible = (errs + warns) > 0 || note.isNotEmpty()
					if (errs > 0) {
						showStatus(owner, status.toString())
					}
					appendBadSelectWarnings(s, status)
				}
				return resultText
			}

			TransformMode.ST -> {
				try {
					val xml = ctx.currentSession.xmlArea.text
					val path = ctx.currentSession.otherActivityPath
					val st = ctx.xmlMapper.readValue(path?.toFile(), SegmentationTree::class.java)
					// корень ищем по MainFlow/Procedures: для ST из MainFlow он на уровень ближе, чем из Procedures
					val rulesDir = path?.let { ProcessPaths.businessRulesDir(it) }
					AppLog.info(
						"ST ${path?.parent?.fileName}: правила из $rulesDir, вход ${xml.length} симв., " +
							"документы на входе: ${DataDocsProcessor.topLevelNames(xml)}",
					)
					// Выходы ST в Layout.xml названы по ConnectionID правила (а не по RuleID); первое сработавшее
					// по ExecutionOrder правило задаёт выход, иначе — AllFalse.
					val firstTrue = st.rules?.ruleList.orEmpty().sortedBy { it.executionOrder }.firstOrNull { ruleRef ->
						val file = rulesDir?.resolve("${ruleRef.ruleID}.xml")?.toFile()
						if (file?.exists() != true) {
							AppLog.warn("ST: файл правила ${ruleRef.ruleID} не найден ($file)")
							return@firstOrNull false
						}
						val rule = ctx.xmlMapper.readValue(file, BusinessRule::class.java)
						val rootNode: Any = RuleParser.parse(ctx.xmlMapper, rule.xmlRule.orEmpty())
						val result = when (rootNode) {
							is Connective -> {
								evaluateBR(xml, rootNode)
							}

							is Quantifier -> {
								val proc = Processor(false)
								val doc = buildDocForXPath(proc, xml)
								val compiler = proc.newXPathCompiler()
								setDefaultNsFromDoc(compiler, doc)
								evalQuantifier(rootNode, compiler, doc)
							}

							else -> false
						}
						AppLog.info("ST: правило ${ruleRef.ruleID} (выход ${ruleRef.connectionID}) = $result")
						if (!result) {
							AppLog.info("ST: почему ${ruleRef.ruleID} = false:\n" + BizRuleEvaluator.explain(xml, rootNode).joinToString("\n"))
						}
						result
					}

					val stResult = firstTrue?.connectionID
						?: "AllFalse"

					if (ctx.autoRunning) {
						AppLog.info("ST: выход $stResult")
					} else {
						Platform.runLater {
							showStatus(owner, "ST result:\n$stResult")
						}
					}

					return stResult
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.SV -> {
				try {
					val propertyFile = ctx.currentSession.otherActivityPath?.toFile()
						?: return ""
					val nextActivityDataDocsInputs = getDataDocsInOut(propertyFile)
						.filter { it.access in arrayOf("Input", "InOut") }
						.map { it.referenceName }
					val neededDataDocs = extractNeededDataDocs(ctx.currentSession.dataDocs!!, nextActivityDataDocsInputs)
					return applySetValues(propertyFile, neededDataDocs)
				} catch (e: Exception) {
					Platform.runLater {
						showStatus(owner, e.localizedMessage)
					}
					throw e
				}
			}

			TransformMode.PR -> {
				val file = ctx.currentSession.otherActivityPath?.toFile()
				val procedureReturn = ctx.xmlMapper.readValue(file, ProcedureReturn::class.java)
				val connectionId = if (procedureReturn.connectionId?.isNotBlank() == true) {
					procedureReturn.connectionId
				} else {
					"Completed"
				}
				return connectionId
			}

			TransformMode.WA -> {
				val wait = ctx.xmlMapper.readValue(ctx.currentSession.otherActivityPath?.toFile(), Wait::class.java)
				val options = wait.commands?.items
					?.map { cmd -> cmd.value!! }
					?: emptyList()
				return showChoiceDialog(ctx.stage, options, "Choose action for: ${wait.referenceName}") ?: ""
			}

			TransformMode.FM -> {
				val form = ctx.xmlMapper.readValue(ctx.currentSession.otherActivityPath?.toFile(), Form::class.java)
				val options = form.commands?.activityCommands
					?.map { cmd -> cmd.value!! }
					?: emptyList()
				return showChoiceDialog(ctx.stage, options, "Choose action for: ${form.referenceName}") ?: ""
			}

			TransformMode.PROCEDURE_RETURN, TransformMode.OTHER -> {
				return ""
			}

		}
	}


	private fun appendBadSelectWarnings(
		session: DocSession,
		status: StringBuilder,
	) {
		val smart = SmartOptions(
			allowAttributeShortcut = true,
			allowDot = false,
			allowDotDot = false
		)
		val bad = collectBadValueOfSelectsSmart(session.xsltArea.text, smart)
		session.xsltBadSelectRanges = bad.map { it.range }
		bad.forEach {
			status.append(
				"WARNING in XSLT [line=${it.line},col=${it.col}]: xsl:value-of select='${it.raw}' не является абсолютным/якорным.\n"
			)
		}
		support.highlightAllMatches(session.xsltArea, state.query, false)
		Platform.runLater { redrawXsltOverlay(session) }
	}
}
