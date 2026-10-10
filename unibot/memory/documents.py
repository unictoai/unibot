"""Ask-my-documents: semantic search over the user's own files.

The agent's workspace accumulates notes, plans, docs and logs. This module
builds a chunked, embedded index over the workspace's text files so the agent
can answer "what did I write about X" with citations to the exact file and
line (``notes/todo.md:42``).

Privacy: the index lives in SQLite next to ``memory.db`` and never leaves the
device. Chunk text is sent only to the user's own configured embeddings
endpoint — the same one memory recall already uses — and only to obtain
vectors. No file content is transmitted anywhere else, ever.

Indexing is incremental and lazy. File mtimes, sizes and content hashes are
tracked, so only new or changed files are (re-)embedded and deleted files are
dropped. Nothing is indexed at startup: the first ``documents_search`` call
triggers it, batched like memory embeddings, with a per-call cap so one
query can never re-embed the world.
"""

from __future__ import annotations

import asyncio
import hashlib
import sqlite3
from array import array
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from unibot.memory.embeddings import Embedder, cosine

# Text files worth indexing. Binaries are skipped separately via a null-byte
# probe, so an unusual-but-textual extension still gets a chance.
TEXT_EXTENSIONS = frozenset(
    {
        ".md", ".markdown", ".txt", ".rst", ".text",
        ".py", ".pyi", ".js", ".ts", ".tsx", ".jsx", ".json", ".jsonl",
        ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
        ".csv", ".tsv", ".log", ".html", ".htm", ".xml", ".css", ".scss",
        ".sh", ".bash", ".zsh", ".sql", ".tex", ".org", ".adoc",
    }
)
# Directories never descended into: VCS, caches, dependencies, build output.
SKIP_DIRS = frozenset(
    {
        ".git", ".hg", ".svn", ".tox", ".mypy_cache", ".pytest_cache",
        "__pycache__", "node_modules", ".venv", "venv", ".env",
        "dist", "build", ".next", ".nuxt", "target",
    }
)
MAX_FILE_BYTES = 512 * 1024  # larger files are skipped, not truncated
CHUNK_CHARS = 2000  # ~500 tokens
CHUNK_OVERLAP = 200  # characters of overlap between consecutive chunks
MAX_CHUNKS_PER_ENSURE = 500  # per-call embedding cap; the rest waits for the next call


@dataclass
class DocChunk:
    """One indexed chunk, with its citation."""

    file: str  # workspace-relative, posix
    line_start: int  # 1-based, inclusive
    line_end: int  # 1-based, inclusive
    text: str
    score: float = 0.0

    def citation(self) -> str:
        if self.line_start == self.line_end:
            return f"{self.file}:{self.line_start}"
        return f"{self.file}:{self.line_start}-{self.line_end}"


def _is_text_file(path: Path) -> bool:
    if path.suffix.lower() not in TEXT_EXTENSIONS:
        return False
    try:
        if path.stat().st_size > MAX_FILE_BYTES:
            return False
        with path.open("rb") as fh:
            # null bytes in the first block: almost certainly binary
            return b"\x00" not in fh.read(8192)
    except OSError:
        return False


def iter_text_files(workspace: Path) -> Iterable[Path]:
    """Text files under ``workspace``, skipping VCS/cache/dependency dirs.

    Hidden directories are never descended into; hidden files are only
    considered at the top level (a ``NOTES.md`` dotfile is a document,
    ``.config/`` is not).
    """
    workspace = workspace.resolve()
    stack = [workspace]
    while stack:
        current = stack.pop()
        try:
            entries = sorted(current.iterdir())
        except OSError:
            continue
        for entry in entries:
            name = entry.name
            if entry.is_dir():
                if name.startswith(".") or name in SKIP_DIRS:
                    continue
                if entry.is_symlink():
                    continue
                stack.append(entry)
            elif entry.is_file() and not entry.is_symlink():
                if name.startswith(".") and entry.parent != workspace:
                    continue
                if _is_text_file(entry):
                    yield entry


def chunk_file(rel_path: str, text: str) -> Iterable[DocChunk]:
    """Split ``text`` into ~500-token chunks with overlap, tracking line numbers.

    Breaks preferentially at blank lines near the target size so chunks end on
    paragraph boundaries; line numbers stay exact for citations.
    """
    lines = [(i + 1, line) for i, line in enumerate(text.splitlines())]
    n = len(lines)
    start = 0
    while start < n:
        chars = 0
        end = start
        while end < n and chars < CHUNK_CHARS:
            chars += len(lines[end][1]) + 1
            end += 1
        # pull the break back to a blank line when one is nearby
        if end < n:
            for b in range(end - 1, max(start, end - 25), -1):
                if not lines[b][1].strip():
                    end = b + 1
                    break
        chunk_text = "\n".join(t for _, t in lines[start:end]).strip()
        if chunk_text:
            yield DocChunk(
                file=rel_path,
                line_start=lines[start][0],
                line_end=lines[end - 1][0],
                text=chunk_text,
            )
        if end >= n:
            break
        # overlap: step the next chunk back by ~CHUNK_OVERLAP chars
        back_chars = 0
        new_start = end
        while new_start > start and back_chars < CHUNK_OVERLAP:
            new_start -= 1
            back_chars += len(lines[new_start][1]) + 1
        start = new_start if new_start > start else start + 1


def file_hash(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(65536), b""):
            h.update(block)
    return h.hexdigest()[:16]


class DocumentStore:
    """SQLite persistence for the document index, next to ``memory.db``.

    ``doc_files`` tracks what was indexed (path → mtime/size/hash);
    ``doc_vectors`` holds one row per chunk per embedding model.
    """

    def __init__(self, path: Path | str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.path))
        self._conn.row_factory = sqlite3.Row
        self._conn.execute(
            """CREATE TABLE IF NOT EXISTS doc_files (
                path TEXT PRIMARY KEY,
                mtime REAL NOT NULL,
                size INTEGER NOT NULL,
                hash TEXT NOT NULL
            )"""
        )
        self._conn.execute(
            """CREATE TABLE IF NOT EXISTS doc_vectors (
                chunk_id TEXT NOT NULL,
                model TEXT NOT NULL,
                hash TEXT NOT NULL,
                file TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                text TEXT NOT NULL,
                vector BLOB NOT NULL,
                PRIMARY KEY (chunk_id, model)
            )"""
        )
        self._conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_doc_vectors_file ON doc_vectors(file, model)"
        )
        self._conn.commit()
        self.index: DocumentIndex | None = None  # set by the app, like MemoryStore.index

    def close(self) -> None:
        self._conn.close()

    # ------------------------------------------------------------ file tracking
    def files(self) -> dict[str, tuple[float, int, str]]:
        """path → (mtime, size, hash) for everything indexed."""
        return {
            row["path"]: (row["mtime"], row["size"], row["hash"])
            for row in self._conn.execute("SELECT path, mtime, size, hash FROM doc_files")
        }

    def put_file(self, path: str, mtime: float, size: int, hash: str) -> None:
        self._conn.execute(
            "INSERT INTO doc_files (path, mtime, size, hash) VALUES (?,?,?,?) "
            "ON CONFLICT(path) DO UPDATE SET mtime=excluded.mtime, size=excluded.size, "
            "hash=excluded.hash",
            (path, mtime, size, hash),
        )
        self._conn.commit()

    def drop_file(self, path: str, model: str | None = None) -> None:
        self._conn.execute("DELETE FROM doc_files WHERE path = ?", (path,))
        if model is None:
            self._conn.execute("DELETE FROM doc_vectors WHERE file = ?", (path,))
        else:
            self._conn.execute(
                "DELETE FROM doc_vectors WHERE file = ? AND model = ?", (path, model)
            )
        self._conn.commit()

    # ------------------------------------------------------------------ vectors
    def vectors(
        self, model: str, path_prefix: str | None = None
    ) -> dict[str, tuple[str, str, int, int, str, list[float]]]:
        """chunk_id → (hash, file, line_start, line_end, text, vector), optionally
        scoped to a path prefix."""
        if path_prefix:
            rows = self._conn.execute(
                "SELECT chunk_id, hash, file, line_start, line_end, text, vector "
                "FROM doc_vectors WHERE model = ? AND (file = ? OR file LIKE ?)",
                (model, path_prefix, path_prefix.rstrip("/") + "/%"),
            )
        else:
            rows = self._conn.execute(
                "SELECT chunk_id, hash, file, line_start, line_end, text, vector "
                "FROM doc_vectors WHERE model = ?",
                (model,),
            )
        out: dict[str, tuple[str, str, int, int, str, list[float]]] = {}
        for row in rows:
            vec = array("f")
            vec.frombytes(row["vector"])
            out[row["chunk_id"]] = (
                row["hash"],
                row["file"],
                row["line_start"],
                row["line_end"],
                row["text"],
                vec.tolist(),
            )
        return out

    def put_vectors(
        self,
        model: str,
        entries: dict[str, tuple[str, str, int, int, str, list[float]]],
    ) -> None:
        """chunk_id → (hash, file, line_start, line_end, text, vector)."""
        self._conn.executemany(
            "INSERT INTO doc_vectors (chunk_id, model, hash, file, line_start, line_end, "
            "text, vector) VALUES (?,?,?,?,?,?,?,?) "
            "ON CONFLICT(chunk_id, model) DO UPDATE SET hash=excluded.hash, "
            "file=excluded.file, line_start=excluded.line_start, line_end=excluded.line_end, "
            "text=excluded.text, vector=excluded.vector",
            [
                (cid, model, h, f, ls, le, t, array("f", v).tobytes())
                for cid, (h, f, ls, le, t, v) in entries.items()
            ],
        )
        self._conn.commit()

    def count_chunks(self, model: str) -> int:
        row = self._conn.execute(
            "SELECT COUNT(*) AS n FROM doc_vectors WHERE model = ?", (model,)
        ).fetchone()
        return int(row["n"])

    # ------------------------------------------------------------ keyword fallback
    def keyword_search(
        self, query: str, limit: int = 10, path_prefix: str | None = None
    ) -> list[DocChunk]:
        """Plain LIKE search over chunk text — the fallback when embeddings are
        unavailable. Duplicates across models are collapsed by chunk text."""
        words = [w for w in query.split() if len(w) >= 3]
        if not words:
            return []
        cond = " AND ".join(["text LIKE ?"] * len(words))
        params: list[Any] = [f"%{w}%" for w in words]
        scope = ""
        if path_prefix:
            scope = " AND (file = ? OR file LIKE ?)"
            params += [path_prefix, path_prefix.rstrip("/") + "/%"]
        # one row per chunk text: the same chunk may be stored under several models
        rows = self._conn.execute(
            f"SELECT DISTINCT file, line_start, line_end, text FROM doc_vectors "
            f"WHERE {cond}{scope} LIMIT ?",
            (*params, limit),
        ).fetchall()
        return [
            DocChunk(
                file=row["file"],
                line_start=row["line_start"],
                line_end=row["line_end"],
                text=row["text"],
            )
            for row in rows
        ]


class DocumentIndex:
    """Incremental embedding index over the workspace's text files."""

    def __init__(
        self, store: DocumentStore, embedder: Embedder | None, workspace: Path
    ):
        self.store = store
        self.embedder = embedder
        self.workspace = workspace.resolve()
        self._lock = asyncio.Lock()

    async def ensure(self) -> bool:
        """Index new/changed files, drop deleted ones. False when embeddings
        cannot be used right now (the caller falls back to keyword search).

        Only files whose mtime/size/hash changed are (re-)embedded, and at
        most MAX_CHUNKS_PER_ENSURE new chunks per call — the rest waits for
        the next call, so one query never re-embeds the world.
        """
        if self.embedder is None or not self.embedder.usable:
            return False
        model = self.embedder.model
        async with self._lock:
            known = self.store.files()
            current: dict[str, tuple[float, int]] = {}
            for path in iter_text_files(self.workspace):
                try:
                    st = path.stat()
                except OSError:
                    continue
                rel = path.relative_to(self.workspace).as_posix()
                current[rel] = (st.st_mtime, st.st_size)
            # deleted files: drop their chunks and tracking rows
            for rel in known.keys() - current.keys():
                self.store.drop_file(rel, model)
            # new or touched files
            changed: list[tuple[str, Path]] = []
            for rel, (mtime, size) in current.items():
                k = known.get(rel)
                if k is None or k[0] != mtime or k[1] != size:
                    changed.append((rel, self.workspace / rel))
            if not changed:
                return self.embedder.usable
            # chunk everything that changed, then embed up to the cap
            pending: list[tuple[str, DocChunk]] = []  # (rel, chunk)
            fresh_meta: dict[str, tuple[float, int, str]] = {}
            for rel, path in sorted(changed):
                try:
                    text = path.read_text(encoding="utf-8", errors="strict")
                except (OSError, UnicodeDecodeError):
                    continue
                digest = hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]
                st = current[rel]
                old = known.get(rel)
                if old is not None and old[2] == digest:
                    # mtime moved but content didn't (e.g. git checkout): no re-embed
                    self.store.put_file(rel, st[0], st[1], digest)
                    continue
                chunks = list(chunk_file(rel, text))
                if not chunks:
                    self.store.put_file(rel, st[0], st[1], digest)
                    continue
                if len(pending) + len(chunks) > MAX_CHUNKS_PER_ENSURE:
                    break  # leave this file (and the rest) for the next call
                self.store.drop_file(rel, model)
                pending.extend((rel, c) for c in chunks)
                fresh_meta[rel] = (st[0], st[1], digest)
            if not pending:
                return self.embedder.usable
            embedded = await self.embedder.embed([c.text for _, c in pending])
            if embedded is None:
                return False
            self.store.put_vectors(
                model,
                {
                    f"{c.file}:{c.line_start}": (file_hash_text(c.text), c.file,
                                                c.line_start, c.line_end, c.text, vec)
                    for (_, c), vec in zip(pending, embedded, strict=True)
                },
            )
            for rel, (mtime, size, digest) in fresh_meta.items():
                self.store.put_file(rel, mtime, size, digest)
            return self.embedder.usable

    async def search(
        self, query: str, limit: int = 10, path: str | None = None
    ) -> list[DocChunk]:
        """Top chunks by meaning; keyword fallback when embeddings are down."""
        query = query.strip()
        if not query:
            return []
        path_prefix = path.strip().strip("/") if path else None
        if not await self.ensure():
            return self.store.keyword_search(query, limit=limit, path_prefix=path_prefix)
        assert self.embedder is not None
        query_vec = await self.embedder.embed([query])
        if not query_vec:
            return self.store.keyword_search(query, limit=limit, path_prefix=path_prefix)
        vectors = self.store.vectors(self.embedder.model, path_prefix)
        scored = [
            (cosine(query_vec[0], vec), DocChunk(file=f, line_start=ls, line_end=le, text=t))
            for _, (_h, f, ls, le, t, vec) in vectors.items()
        ]
        scored.sort(key=lambda p: -p[0])
        chunks = []
        for score, chunk in scored[:limit]:
            chunk.score = score
            chunks.append(chunk)
        return chunks

    def status(self) -> dict[str, Any]:
        e = self.embedder
        return {
            "workspace": str(self.workspace),
            "model": e.model if e else None,
            "embeddings": e.status if e else "off",
            "files": len(self.store.files()),
            "chunks": self.store.count_chunks(e.model) if e else 0,
        }


def file_hash_text(text: str) -> str:
    return hashlib.sha1(text.strip().encode("utf-8")).hexdigest()[:16]


__all__ = [
    "CHUNK_CHARS",
    "CHUNK_OVERLAP",
    "MAX_CHUNKS_PER_ENSURE",
    "MAX_FILE_BYTES",
    "SKIP_DIRS",
    "TEXT_EXTENSIONS",
    "DocChunk",
    "DocumentIndex",
    "DocumentStore",
    "chunk_file",
    "file_hash",
    "iter_text_files",
]
