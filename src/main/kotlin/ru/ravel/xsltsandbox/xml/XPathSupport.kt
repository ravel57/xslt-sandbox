package ru.ravel.xsltsandbox.xml

import java.io.StringReader
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamSource
import net.sf.saxon.s9api.Axis
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.SaxonApiException
import net.sf.saxon.s9api.XPathCompiler
import net.sf.saxon.s9api.XdmNode
import net.sf.saxon.s9api.XdmNodeKind
import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.InputSource
import ru.ravel.xsltsandbox.models.bizrule.XPath

object XPathSupport {
	/**
	 * Универсальный разбор текста для XPath: сначала XML; если похоже на HTML — TagSoup;
	 * иначе — оборачиваем XML‑фрагмент без корня и вырезаем DOCTYPE.
	 * */
	fun buildDocForXPath(proc: Processor, text: String): XdmNode {
		val builder = proc.newDocumentBuilder()
		val trimmed = text.trim()
		require(trimmed.isNotEmpty()) { "Selected text is empty." }

		// 1) Обычный XML
		try {
			return builder.build(StreamSource(StringReader(trimmed)))
		} catch (_: SaxonApiException) {
			// пробуем дальше
		}

		// 2) HTML → TagSoup
		val looksHtml =
			Regex("""<!DOCTYPE\s+html""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
					Regex("""<html(\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
					Regex("""<body(\s|>)""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
		if (looksHtml) {
			val parser = Parser()
			val src = SAXSource(parser, InputSource(StringReader(trimmed)))
			return builder.build(src)
		}

		// 3) XML‑фрагмент без общего корня
		val noDoctype = trimmed.replace(Regex("""<!DOCTYPE[\s\S]*?>""", RegexOption.IGNORE_CASE), "")
		val wrapped = "<__root>$noDoctype</__root>"
		return builder.build(StreamSource(StringReader(wrapped)))
	}


	/**
	 * Автонастройка default element namespace для XPath из корневого элемента документа.
	 * Для HTML (TagSoup/XHTML) это включит поддержку выражений без префикса: //h1, //section/p.
	 * */
	fun setDefaultNsFromDoc(compiler: XPathCompiler, doc: XdmNode) {
		val it = doc.axisIterator(Axis.CHILD)
		while (it.hasNext()) {
			val n = it.next() as XdmNode
			if (n.nodeKind == XdmNodeKind.ELEMENT) {
				val ns = n.nodeName?.namespaceUri?.toString()
				if (!ns.isNullOrEmpty()) {
					compiler.declareNamespace("", ns)
					compiler.declareNamespace("h", ns)
				}
				break
			}
		}
	}
}
