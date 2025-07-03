package ru.ravel.xsltsandbox.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class BlockSerialized @JsonCreator constructor(
	@JsonProperty("id") val id: UUID = UUID.randomUUID(),
	@JsonProperty("x") val x: Double = 0.0,
	@JsonProperty("y") val y: Double = 0.0,
	@JsonProperty("name") val name: String = "",
	@JsonProperty("blockType") val blockType: String = "",
	@JsonProperty("inputFormat") val inputFormat: InputFormatType? = null,
	@JsonProperty("code") val code: String? = null,
	@JsonProperty("dataDocs") val dataDocs: String? = null,
	@JsonProperty("otherInfo") val otherInfo: String? = null,
	@JsonProperty("inputCount") val inputCount: Int = 1,
	@JsonProperty("outputCount") val outputCount: Int = 1,
	@JsonProperty("inputNames") val inputNames: List<String>? = null,
	@JsonProperty("outputNames") val outputNames: List<String>? = null,
	@JsonProperty("outputsData") val outputsData: MutableList<MutableMap<String, Any>>? = null,
	@JsonProperty("packagesNames") val packagesNames: MutableList<String>? = null,
)