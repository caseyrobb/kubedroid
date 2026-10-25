package com.neozeon.kubedroid.qrexport

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Pre-export cleanup (spec 03 "Auth methods", human decision 2026-10-24):
 * kubedroid rejects `exec:` and `auth-provider` users at import, so the
 * export tool removes those blocks instead of emitting QRs that fail to
 * import. Everything else (tokens, client certs, CAs, contexts) is kept
 * verbatim; users left without credentials import as anonymous.
 */
data class StripResult(val yaml: String, val removals: List<String>)

object KubeconfigStripper {

    /** Keys inside `users[].user` that kubedroid cannot use (spec 03). */
    private val STRIPPED_KEYS = listOf("exec", "auth-provider")

    @Suppress("UNCHECKED_CAST")
    fun strip(raw: String): StripResult {
        val loaded = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("input is not valid YAML: ${e.message?.lineSequence()?.firstOrNull()}", e)
        }
        require(loaded is Map<*, *>) { "input is not a kubeconfig mapping" }

        val removals = mutableListOf<String>()
        val root = LinkedHashMap((loaded as Map<Any, Any>).mapKeys { it.key.toString() })

        val users = root["users"]
        if (users is List<*>) {
            root["users"] = users.map { entry ->
                if (entry !is Map<*, *>) return@map entry
                val userEntry = LinkedHashMap((entry as Map<Any, Any>).mapKeys { it.key.toString() })
                val name = userEntry["name"] as? String ?: "<unnamed>"
                val body = userEntry["user"]
                if (body !is Map<*, *>) return@map userEntry
                val user = LinkedHashMap((body as Map<Any, Any>).mapKeys { it.key.toString() })
                for (key in STRIPPED_KEYS) {
                    if (user.remove(key) != null) {
                        removals += "user '$name': stripped '$key' block (kubedroid does not support it)"
                    }
                }
                if (user.isEmpty()) {
                    removals += "user '$name': no credentials left after stripping; " +
                        "will import as anonymous"
                }
                userEntry["user"] = user
                userEntry
            }
        }

        return StripResult(dump(root), removals)
    }

    private fun dump(root: Map<String, Any?>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            // Never wrap long base64 scalars: the app decodes credential
            // fields with the standard Base64 decoder, which rejects the
            // spaces a folded scalar would introduce.
            width = Int.MAX_VALUE
        }
        return Yaml(options).dump(root)
    }
}
