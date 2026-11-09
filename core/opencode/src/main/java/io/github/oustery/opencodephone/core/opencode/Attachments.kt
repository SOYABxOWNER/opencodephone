package io.github.oustery.opencodephone.core.opencode

/**
 * Remote-mode attachment delivery (functional audit F-002).
 *
 * A remote opencode server cannot read files that live on the phone, so the
 * V1 `file://` part URL only works when the app talks to its own local
 * environment. In Remote mode, text-like attachments are delivered inline as
 * prompt text instead, and binary attachments are refused up front instead of
 * failing silently inside the agent run.
 */
object Attachments {

    /** Text larger than this is not inlined into the prompt. */
    const val INLINE_LIMIT_BYTES: Int = 512 * 1024

    private val inlineableMimes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-javascript",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/x-sh",
        "application/sql",
        "application/graphql",
    )

    private val inlineableExtensions = setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini",
        "cfg", "conf", "properties", "csv", "tsv", "html", "htm", "css",
        "scss", "js", "mjs", "cjs", "jsx", "ts", "tsx", "kt", "kts", "java",
        "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "swift",
        "dart", "php", "sh", "bash", "zsh", "sql", "gradle", "gradlew",
        "gitignore", "gitattributes", "editorconfig", "env", "lock", "proto",
        "graphql", "dockerfile", "makefile", "cmake", "vue", "svelte",
    )

    /** True when the attachment can be delivered as prompt text. */
    fun isInlineable(mime: String, filename: String): Boolean {
        val m = mime.trim().lowercase()
        if (m.startsWith("text/")) return true
        if (m in inlineableMimes) return true
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in inlineableExtensions
    }

    /**
     * Fenced text block that carries one file's content inside the prompt.
     * Deliberately plain: the agent reads it like a pasted snippet.
     */
    fun inlineBlock(filename: String, content: String): String {
        val ext = filename.substringAfterLast('.', "")
            .lowercase().takeIf { it.isNotEmpty() && it.length <= 8 && it in inlineableExtensions }
        return buildString {
            append("Attached file \"").append(filename).append("\":\n```")
            if (ext != null) append(ext)
            append('\n').append(content.trimEnd()).append('\n').append("```")
        }
    }
}
