"""Ask-my-documents: semantic search over the user's own files.

The agent calls this to answer "what did I write about X". Results carry
``file:line`` citations — the agent must cite them when answering, e.g.
``notes/todo.md:42``.

Privacy: the index lives on this device (SQLite next to memory.db) and never
leaves it. Chunk text goes only to the user's own configured embeddings
endpoint, the same one memory recall uses.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from unibot.memory.documents import DocumentStore
from unibot.schema import RiskLevel, ToolResult
from unibot.tools.base import BaseTool, CallAssessment


def _short(value: Any, limit: int = 80) -> str:
    text = str(value or "").strip().replace("\n", " ")
    return text if len(text) <= limit else text[: limit - 1] + "…"


class DocumentsSearch(BaseTool):
    name: str = "documents_search"
    description: str = (
        "Search the user's own documents and notes by meaning (semantic search over "
        "their files: markdown, text, code, CSV, logs). Use for 'what did I write about X', "
        "'find my notes on Y', 'where did I mention Z'. Returns matching passages with "
        "file:line citations — always cite these (e.g. notes/todo.md:42) when answering "
        "from them, so the user can verify. `path` optionally scopes the search to a "
        "folder or file, relative to the workspace."
    )
    parameters: dict[str, Any] = {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "what to look for, in plain words"},
            "path": {
                "type": "string",
                "description": "optional folder or file to search within, relative to the workspace",
            },
            "limit": {"type": "integer", "description": "max passages (default 5)"},
        },
        "required": ["query"],
    }
    risk: RiskLevel = RiskLevel.SAFE
    reads_private_data: bool = True
    documents: DocumentStore
    workspace: Path

    def assess(self, args: dict[str, Any]) -> CallAssessment:
        a = super().assess(args)
        scope = f" in {args['path']}" if args.get("path") else ""
        a.summary = f"documents: {_short(args.get('query'))}{scope}"
        return a

    async def execute(
        self,
        query: str = "",
        path: str | None = None,
        limit: int = 5,
        **_: Any,
    ) -> ToolResult:
        query = (query or "").strip()
        if not query:
            return ToolResult.fail("`query` is required")
        index = self.documents.index
        if index is None:
            return ToolResult(
                output="Document search is unavailable (embeddings are off and no index "
                "was built). The files tool can still list and read files directly."
            )
        # scope the search inside the workspace; never outside it
        scope: str | None = None
        if path:
            candidate = (self.workspace / path.strip().lstrip("/")).resolve()
            ws = self.workspace.resolve()
            if candidate != ws and ws not in candidate.parents:
                return ToolResult.fail(f"`path` must stay inside the workspace: {path}")
            scope = candidate.relative_to(ws).as_posix()
            if scope == ".":
                scope = None
        try:
            chunks = await index.search(query, limit=max(1, min(int(limit or 5), 20)), path=scope)
        except Exception as exc:  # noqa: BLE001 — search must never break the turn
            return ToolResult.fail(f"document search failed: {exc}")
        if not chunks:
            hint = f" in {scope}" if scope else ""
            return ToolResult(
                output=f"No matching passages{hint}. The documents index covers the "
                "workspace's text files; try different wording or a broader query."
            )
        parts = []
        for chunk in chunks:
            parts.append(f"{chunk.citation()}\n{chunk.text}")
        return ToolResult(output="\n\n---\n\n".join(parts))


__all__ = ["DocumentsSearch"]
