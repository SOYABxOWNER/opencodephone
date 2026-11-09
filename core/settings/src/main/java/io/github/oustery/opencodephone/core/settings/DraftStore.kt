package io.github.oustery.opencodephone.core.settings

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Crash-safe persistence for unsaved user work (functional audit F-003).
 *
 * In-memory ViewModel state does not survive Android process death, so the
 * chat draft (text + pending attachments) and the file editor buffer are
 * mirrored into small JSON files under app-private storage. Writes are
 * atomic (temp file + rename); a corrupted or missing file reads back as
 * null instead of throwing. Secrets never enter this store: it holds only
 * user-typed text and workspace-relative attachment metadata.
 *
 * The store is intentionally synchronous; callers are responsible for
 * invoking it from a background dispatcher.
 */
class DraftStore(private val dir: File) {

    /** One pending chat attachment, mirrored from the composer. */
    data class StoredAttachment(
        val id: String,
        val displayName: String,
        val mime: String,
        val size: Long,
        val absolutePath: String,
        val envPath: String,
    )

    data class ChatDraft(
        val text: String,
        val attachments: List<StoredAttachment>,
    )

    data class EditorDraft(
        /** Absolute path of the file being edited. */
        val path: String,
        val content: String,
    )

    // ---------------------------------------------------------------- Chat

    fun readChat(): ChatDraft? = read(FILE_CHAT) { json ->
        val attachments = buildList {
            val array = json.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    StoredAttachment(
                        id = item.optString("id"),
                        displayName = item.optString("displayName"),
                        mime = item.optString("mime"),
                        size = item.optLong("size"),
                        absolutePath = item.optString("absolutePath"),
                        envPath = item.optString("envPath"),
                    ),
                )
            }
        }
        ChatDraft(text = json.optString("text"), attachments = attachments)
    }

    fun writeChat(draft: ChatDraft) {
        val json = JSONObject()
        json.put("text", draft.text)
        val array = JSONArray()
        for (attachment in draft.attachments) {
            array.put(
                JSONObject()
                    .put("id", attachment.id)
                    .put("displayName", attachment.displayName)
                    .put("mime", attachment.mime)
                    .put("size", attachment.size)
                    .put("absolutePath", attachment.absolutePath)
                    .put("envPath", attachment.envPath),
            )
        }
        json.put("attachments", array)
        writeAtomically(FILE_CHAT, json)
    }

    fun clearChat() = delete(FILE_CHAT)

    // -------------------------------------------------------------- Editor

    fun readEditor(): EditorDraft? = read(FILE_EDITOR) { json ->
        EditorDraft(path = json.optString("path"), content = json.optString("content"))
    }

    fun writeEditor(draft: EditorDraft) {
        val json = JSONObject().put("path", draft.path).put("content", draft.content)
        writeAtomically(FILE_EDITOR, json)
    }

    fun clearEditor() = delete(FILE_EDITOR)

    // ------------------------------------------------------------ Internals

    private inline fun <T> read(name: String, parse: (JSONObject) -> T): T? {
        val file = File(dir, name)
        if (!file.isFile) return null
        return try {
            parse(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(name: String, json: JSONObject) {
        synchronized(this) {
            try {
                dir.mkdirs()
                val target = File(dir, name)
                val temp = File(dir, "$name.tmp")
                temp.writeText(json.toString(), Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    // Rename across the same directory only fails on exotic
                    // filesystems; fall back to a direct best-effort write.
                    target.writeText(json.toString(), Charsets.UTF_8)
                    temp.delete()
                }
            } catch (_: Exception) {
                // A failed draft mirror must never break the feature itself.
            }
        }
    }

    private fun delete(name: String) {
        synchronized(this) {
            runCatching { File(dir, name).delete() }
            runCatching { File(dir, "$name.tmp").delete() }
        }
    }

    private companion object {
        const val FILE_CHAT = "chat_draft.json"
        const val FILE_EDITOR = "editor_draft.json"
    }
}
