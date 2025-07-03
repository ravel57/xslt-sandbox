module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;
	requires java.xml;
	requires org.fxmisc.richtext;
	requires org.fxmisc.flowless;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.dataformat.xml;
	requires org.apache.groovy;
	requires org.yaml.snakeyaml;
	requires java.scripting;

	opens ru.ravel.xsltsandbox to javafx.fxml, com.fasterxml.jackson.databind;
	opens ru.ravel.xsltsandbox.dto.editor;
	exports ru.ravel.xsltsandbox;
}