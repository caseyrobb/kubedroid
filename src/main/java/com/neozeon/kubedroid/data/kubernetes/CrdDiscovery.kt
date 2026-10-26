package com.neozeon.kubedroid.data.kubernetes

/**
 * Spec 07 "CrdDiscovery": parse `apiextensions.k8s.io/v1`
 * CustomResourceDefinition objects (unstructured maps) into dynamic
 * [CatalogEntry] shapes so CRs flow through the spec 04 list/detail and
 * spec 06 edit/delete paths unchanged.
 *
 * Skip rules (spec 07): one malformed CRD must never blank the whole list
 * — [parse] returns null for CRDs missing required identity fields or a
 * served version, and callers drop nulls.
 */

/** One `additionalPrinterColumns` entry (priority-0 columns are rendered). */
data class PrinterColumn(
    val name: String,
    val type: String,
    val jsonPath: String,
    val priority: Int,
    val description: String?,
)

/** Parsed CRD identity + per-version presentation data. */
data class CrdDefinition(
    val name: String,                      // metadata.name: plural.group
    val group: String,
    val kind: String,
    val listKind: String,
    val plural: String,
    val singular: String?,
    val shortNames: List<String>,
    val namespaced: Boolean,
    val servedVersions: List<String>,      // spec.versions[] served=true, server order kept
    val storageVersion: String?,           // spec.versions[] storage=true
    val printerColumns: Map<String, List<PrinterColumn>>, // keyed by version name
) {
    /** Q2 human decision 2026-10-26: storage version when served, else first served. */
    val defaultVersion: String
        get() = storageVersion?.takeIf { it in servedVersions } ?: servedVersions.first()

    fun columnsFor(version: String): List<PrinterColumn> =
        printerColumns[version].orEmpty().filter { it.priority == 0 }
}

object CrdDiscovery {

    const val CRD_API_VERSION = "apiextensions.k8s.io/v1"
    const val CRD_RESOURCE = "customresourcedefinitions"

    /** Cluster-scoped entry used to fetch the CRD list through the gateway. */
    val crdListEntry = CatalogEntry(
        displayName = "Custom Resources",
        kind = "CustomResourceDefinition",
        group = CatalogGroup.CUSTOM,
        apiVersion = CRD_API_VERSION,
        resource = CRD_RESOURCE,
        namespaced = false,
    ) { obj ->
        val served = obj.atList("spec", "versions")
            ?.count { it.asMap()?.flag("served") == true } ?: 0
        val scope = if (obj.atStr("spec", "scope") == "Cluster") "cluster" else "namespaced"
        StatusLine("$served served version${if (served == 1) "" else "s"}", scope)
    }

    /** Parse one CRD object; null = malformed, caller skips it (never fatal). */
    fun parse(obj: Map<String, Any?>): CrdDefinition? {
        val spec = obj.asMap()?.get("spec").asMap() ?: return null
        val group = spec.atStr("group")?.takeIf { it.isNotBlank() } ?: return null
        val names = spec.atMap("names") ?: return null
        val plural = names.atStr("plural")?.takeIf { it.isNotBlank() } ?: return null
        val kind = names.atStr("kind")?.takeIf { it.isNotBlank() } ?: return null
        val scope = spec.atStr("scope") ?: return null
        if (scope != "Namespaced" && scope != "Cluster") return null

        val versions = spec.atList("versions") ?: return null
        val served = mutableListOf<String>()
        var storage: String? = null
        val columns = mutableMapOf<String, List<PrinterColumn>>()
        for (raw in versions) {
            val v = raw.asMap() ?: continue
            val vName = v.atStr("name")?.takeIf { it.isNotBlank() } ?: continue
            if (v.flag("served") == true) served += vName
            if (v.flag("storage") == true) storage = vName
            columns[vName] = v.atList("additionalPrinterColumns").orEmpty().mapNotNull { col ->
                val c = col.asMap() ?: return@mapNotNull null
                val cName = c.atStr("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = c.atStr("jsonPath")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PrinterColumn(
                    name = cName,
                    type = c.atStr("type") ?: "string",
                    jsonPath = path,
                    priority = (c["priority"] as? Number)?.toInt() ?: 0,
                    description = c.atStr("description"),
                )
            }
        }
        if (served.isEmpty()) return null  // nothing browsable — treat as malformed

        val shortNames = names.atList("shortNames").orEmpty().mapNotNull { it as? String }
        return CrdDefinition(
            name = obj.asMap()?.atMap("metadata")?.atStr("name") ?: "$plural.$group",
            group = group,
            kind = kind,
            listKind = names.atStr("listKind")?.takeIf { it.isNotBlank() } ?: "${kind}List",
            plural = plural,
            singular = names.atStr("singular")?.takeIf { it.isNotBlank() },
            shortNames = shortNames,
            namespaced = scope == "Namespaced",
            servedVersions = served,
            storageVersion = storage,
            printerColumns = columns,
        )
    }

    /** Parse a CRD list response; malformed items are skipped, never fatal. */
    fun parseList(json: Map<String, Any?>): List<CrdDefinition> =
        (json["items"] as? List<Any?>).orEmpty()
            .mapNotNull { it.asMap()?.let { o -> parse(o) } }

    /** Build the dynamic catalog entry for one CRD at one (served) version. */
    fun entry(crd: CrdDefinition, version: String): CatalogEntry {
        val cols = crd.columnsFor(version)
        return CatalogEntry(
            displayName = pluralize(crd.kind),
            kind = crd.kind,
            group = CatalogGroup.CUSTOM,
            apiVersion = "${crd.group}/$version",
            resource = crd.plural,
            namespaced = crd.namespaced,
        ) { obj ->
            if (cols.isEmpty()) {
                // Fallback (spec 07 "CR lists"): name/namespace are already
                // rendered by the row; age comes from createdAt. The status
                // line stays neutral.
                StatusLine("custom resource", null)
            } else {
                val values = cols.map { PrinterColumnEvaluator.render(it.jsonPath, obj) }
                StatusLine(values.first(), values.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" · "))
            }
        }
    }

    /** kubectl-style short name for the entry (IngressRoute → IngressRoutes). */
    internal fun pluralize(kind: String): String = when {
        kind.endsWith("s") || kind.endsWith("x") || kind.endsWith("z") ||
            kind.endsWith("ch") || kind.endsWith("sh") -> kind + "es"
        kind.endsWith("y") && kind.length > 1 &&
            kind[kind.length - 2] !in "aeiou" -> kind.dropLast(1) + "ies"
        else -> kind + "s"
    }

    // ---- unstructured-map helpers (same Gson shape as ResourceCatalog) ----

    private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
    private fun Any?.asList(): List<Any?>? = this as? List<Any?>
    private fun Map<*, *>.atStr(vararg path: String): String? = at(*path) as? String
    private fun Map<*, *>.flag(vararg path: String): Boolean? = at(*path) as? Boolean
    private fun Map<*, *>.atMap(vararg path: String): Map<String, Any?>? = at(*path).asMap()
    private fun Map<*, *>.atList(vararg path: String): List<Any?>? = at(*path).asList()

    private fun Map<*, *>.at(vararg path: String): Any? {
        var cur: Any? = this
        for (key in path) {
            cur = cur.asMap()?.get(key) ?: return null
        }
        return cur
    }
}

/**
 * Spec 07 "Printer columns": dotted-path jsonPath subset evaluator.
 * Supports `{.a.b.c}` / `.a.b.c` against the unstructured map; scalars
 * render (numbers drop the Gson `.0`), absent keys and unsupported
 * expressions (arrays, objects, filters, ranges) render `-` — a bad
 * column never breaks the row.
 */
object PrinterColumnEvaluator {

    fun render(jsonPath: String?, obj: Map<String, Any?>): String {
        val cleaned = jsonPath?.trim()?.removePrefix("{")?.removeSuffix("}")?.removePrefix(".") ?: return DASH
        if (cleaned.isEmpty()) return DASH
        var cur: Any? = obj
        for (key in cleaned.split('.')) {
            if (key.isEmpty()) return DASH  // empty segment = unsupported syntax
            cur = (cur as? Map<*, *>)?.get(key) ?: return DASH
        }
        return when (cur) {
            null -> DASH
            is Map<*, *>, is List<*> -> DASH
            is Boolean -> cur.toString()
            is Number -> {
                val d = cur.toDouble()
                if (d == d.toLong().toDouble()) cur.toLong().toString() else cur.toString()
            }
            else -> cur.toString()
        }
    }

    private const val DASH = "-"
}