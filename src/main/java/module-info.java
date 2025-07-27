module ru.ravel.xsltsandbox {
	requires javafx.controls;
	requires javafx.fxml;
	requires kotlin.stdlib;
	requires java.xml;
	requires org.fxmisc.richtext;
	requires org.fxmisc.flowless;
	requires com.fasterxml.jackson.kotlin;
	requires com.fasterxml.jackson.databind;
	requires Saxon.HE;
	requires tagsoup;


	opens ru.ravel.xsltsandbox to javafx.fxml, com.fasterxml.jackson.databind;
	exports ru.ravel.xsltsandbox;
}