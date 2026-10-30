package dev.androidmcp.core

import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg entries: Pair<String, Any?>): JSONObject = JSONObject().also { out ->
    entries.forEach { (key, value) -> out.put(key, value ?: JSONObject.NULL) }
}
fun arr(vararg values: Any?): JSONArray = JSONArray(values.toList())
class ToolFailure(message: String) : Exception(message)

data class Tool(
    val name: String,
    val description: String,
    val properties: JSONObject = obj(),
    val required: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val serial: Boolean = false,
    val execute: (JSONObject) -> JSONObject,
) {
    fun schema() = obj(
        "name" to name, "description" to description,
        "inputSchema" to obj("type" to "object", "properties" to properties,
            "required" to JSONArray(required), "additionalProperties" to false),
        "annotations" to obj("readOnlyHint" to readOnly, "destructiveHint" to !readOnly,
            "idempotentHint" to readOnly, "openWorldHint" to !readOnly),
    )

    fun validate(args: JSONObject) {
        required.forEach { if (!args.has(it) || args.isNull(it)) throw ToolFailure("Missing argument: $it") }
        args.keys().forEach { key ->
            val spec = properties.optJSONObject(key) ?: throw ToolFailure("Unknown argument: $key")
            val v = args.get(key)
            val valid = when (spec.getString("type")) {
                "string" -> v is String
                "integer" -> v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble()
                "boolean" -> v is Boolean
                else -> false
            }
            if (!valid) throw ToolFailure("Invalid type for $key")
            if (v is String && (v.length < spec.optInt("minLength", 0) || v.length > spec.optInt("maxLength", 4096)))
                throw ToolFailure("Invalid length for $key")
            if (v is Number && (v.toDouble() < spec.optDouble("minimum", -Double.MAX_VALUE) ||
                    v.toDouble() > spec.optDouble("maximum", Double.MAX_VALUE))) throw ToolFailure("$key is out of range")
            spec.optJSONArray("enum")?.let { choices ->
                if ((0 until choices.length()).none { choices.get(it) == v }) throw ToolFailure("Invalid value for $key")
            }
        }
    }
}

fun stringProp(description: String, max: Int = 256, min: Int = 1) = obj(
    "type" to "string", "description" to description, "minLength" to min, "maxLength" to max)
fun intProp(description: String, min: Int, max: Int) = obj(
    "type" to "integer", "description" to description, "minimum" to min, "maximum" to max)
