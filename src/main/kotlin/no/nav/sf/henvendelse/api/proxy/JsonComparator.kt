package no.nav.sf.henvendelse.api.proxy

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

object JsonComparator {
    fun jsonEquals(
        a: String,
        b: String,
    ): Boolean =
        try {
            jsonEquals(JsonParser.parseString(a), JsonParser.parseString(b))
        } catch (e: Exception) {
            File("/tmp/jsonSyntaxError").writeText(e.stackTraceToString())
            false
        }

    fun jsonEquals(
        a: JsonElement?,
        b: JsonElement?,
    ): Boolean {
        if (a == null || b == null) return a == b // Both null = true, one null = false

        if (a.isJsonPrimitive && b.isJsonPrimitive) {
            return a == b // Compare primitive values directly
        }

        if (a.isJsonObject && b.isJsonObject) {
            val objA = a.asJsonObject
            val objB = b.asJsonObject

            if (objA.keySet() != objB.keySet()) return false // Keys must match

            return objA.keySet().all { key -> jsonEquals(objA[key], objB[key]) }
        }

        if (a.isJsonArray && b.isJsonArray) {
            val arrA = a.asJsonArray
            val arrB = b.asJsonArray

            if (arrA.size() != arrB.size()) return false // Lengths must match

            val setA = arrA.map { it.toString() }.toSet()
            val setB = arrB.map { it.toString() }.toSet()

            return setA == setB // Compare as sets (unordered)
        }

        return false // Mismatched types
    }

    fun numberOfJournalPostIdNull(json: String): Int {
        val regex = "\"journalpostId\"\\s*:\\s*null,"
        return regex.toRegex().findAll(json).count()
    }

    fun numberOfEmptyMeldinger(json: String): Int {
        val regex = "\"meldinger\"\\s*:\\s*\\[\\s*\\]".toRegex()
        return regex.findAll(json).count()
    }

    fun numberOfFnrFields(json: String): Int {
        val regex = "\"fnr\"".toRegex()
        return regex.findAll(json).count()
    }

    fun findMissingElements(
        json1: String,
        json2: String,
    ): List<JsonElement> =
        try {
            val list1 = JsonParser.parseString(json1).asJsonArray
            val list2 = JsonParser.parseString(json2).asJsonArray

            val set1 = list1.map { it.toString() }.toSet()
            val set2 = list2.map { it.toString() }.toSet()

            // Elements in json1 but missing in json2
            val missing = set1 - set2
            missing.map { JsonParser.parseString(it) }
        } catch (e: Exception) {
            File("/tmp/aJsonSyntaxError").writeText(e.stackTraceToString())
            emptyList()
        }
}
