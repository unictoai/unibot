package ai.unicto.unibot.slashcommands

/**
 * Built-in `/docs` slash command — "Ask my documents".
 *
 * Unlike user-defined commands (which live in [SlashCommandStore]'s
 * SharedPreferences), this one is defined in code and seeded into the store
 * on first launch. Seeding — rather than a parallel built-in list — keeps a
 * single code path for the `/` menu rows, send-path expansion, and the
 * enable/disable UI in the slash-commands settings screen.
 *
 * The template instructs the agent to use the `documents_search` tool and to
 * cite facts as `file:line` / `file:start-end`; the chat renderer turns
 * those citations into tappable links (see DocCitationLinkifier).
 */
object DocsSlashCommand {
    /** Stable id so the seeded row stays recognizable (e.g. for its menu icon). */
    const val BUILTIN_ID = "builtin-docs"

    const val TRIGGER = "docs"

    const val DESCRIPTION = "Ask my documents"

    const val TEMPLATE =
        "Search my documents for '{args}' using the documents_search tool. " +
            "Answer using what you find, citing each fact as file:line " +
            "(e.g. notes/todo.md:42) or file:start-end for a range " +
            "(e.g. notes/todo.md:42-48). If nothing relevant is found, say so plainly."

    fun defaultCommand(): CustomSlashCommand = CustomSlashCommand(
        id = BUILTIN_ID,
        trigger = TRIGGER,
        description = DESCRIPTION,
        template = TEMPLATE,
        enabled = true,
    )
}
