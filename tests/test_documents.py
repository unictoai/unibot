"""Tests for ask-my-documents: chunking, incremental indexing, and the tool."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from unibot.memory.documents import (
    DocChunk,
    DocumentIndex,
    DocumentStore,
    chunk_file,
    iter_text_files,
)
from unibot.schema import RiskLevel
from unibot.tools.documents_tool import DocumentsSearch


class FakeEmbedder:
    """Deterministic vectors: no network, no endpoint."""

    def __init__(self, dims: int = 8):
        self.dims = dims
        self.model = "fake-test"
        self.calls: list[list[str]] = []

    @property
    def usable(self) -> bool:
        return True

    async def embed(self, texts: list[str]):
        self.calls.append(texts)
        out = []
        for t in texts:
            # cheap deterministic pseudo-vector from the text
            seed = sum(ord(c) for c in t) % 97
            out.append([float((seed + i) % 11) / 10.0 for i in range(self.dims)])
        return out


def make_index(tmp_path: Path, workspace: Path | None = None):
    ws = workspace or (tmp_path / "ws")
    ws.mkdir(parents=True, exist_ok=True)
    store = DocumentStore(tmp_path / "documents.db")
    index = DocumentIndex(store, FakeEmbedder(), ws)
    store.index = index
    return store, index, ws


# ------------------------------------------------------------------ chunking
def test_chunk_file_tracks_lines_and_citations():
    text = "\n".join(f"line {i} content here" for i in range(1, 61))
    chunks = list(chunk_file("notes/todo.md", text))
    assert chunks, "expected at least one chunk"
    assert chunks[0].line_start == 1
    assert chunks[0].citation().startswith("notes/todo.md:1")
    # line numbers are contiguous and cover the file
    assert chunks[-1].line_end == 60
    for c in chunks:
        assert 1 <= c.line_start <= c.line_end <= 60
        assert c.text.strip()


def test_chunk_overlap_and_blank_line_breaks():
    paras = []
    for p in range(25):
        paras.append("\n".join(f"para {p} line {i}" for i in range(8)))
    text = "\n\n".join(paras)
    chunks = list(chunk_file("doc.md", text))
    assert len(chunks) >= 2
    # overlap: a later chunk starts before the earlier one ended
    assert chunks[1].line_start <= chunks[0].line_end


def test_chunk_empty_text_yields_nothing():
    assert list(chunk_file("empty.md", "")) == []
    assert list(chunk_file("blank.md", "\n\n  \n")) == []


# ------------------------------------------------------------------ file walk
def test_iter_text_files_skips_binaries_and_vcs(tmp_path: Path):
    ws = tmp_path / "ws"
    (ws / "notes").mkdir(parents=True)
    (ws / "notes" / "todo.md").write_text("buy milk")
    (ws / "notes" / "photo.png").write_bytes(b"\x89PNG\r\n\x1a\n\x00\x01\x02")
    (ws / ".git" / "objects").mkdir(parents=True)
    (ws / ".git" / "objects" / "x.md").write_text("not a document")
    (ws / "__pycache__").mkdir()
    (ws / "__pycache__" / "c.md").write_text("cache")
    found = {p.relative_to(ws).as_posix() for p in iter_text_files(ws)}
    assert found == {"notes/todo.md"}


def test_iter_text_files_skips_huge_files(tmp_path: Path):
    ws = tmp_path / "ws"
    ws.mkdir()
    (ws / "big.log").write_bytes(b"x" * (600 * 1024))
    (ws / "small.log").write_text("ok")
    found = {p.relative_to(ws).as_posix() for p in iter_text_files(ws)}
    assert found == {"small.log"}


# ------------------------------------------------------------------ store
def test_store_vectors_roundtrip(tmp_path: Path):
    store = DocumentStore(tmp_path / "d.db")
    store.put_vectors(
        "m",
        {"c1": ("h1", "a.md", 1, 3, "hello world", [0.1, 0.2])},
    )
    vecs = store.vectors("m")
    assert set(vecs) == {"c1"}
    h, f, ls, le, t, v = vecs["c1"]
    assert (f, ls, le, t) == ("a.md", 1, 3, "hello world")
    assert v == pytest.approx([0.1, 0.2], abs=1e-6)
    assert store.count_chunks("m") == 1


def test_keyword_search_fallback(tmp_path: Path):
    store = DocumentStore(tmp_path / "d.db")
    store.put_vectors(
        "m",
        {
            "c1": ("h1", "a.md", 1, 2, "the landlord is Bob Li", [0.1]),
            "c2": ("h2", "b.md", 5, 6, "buy milk tomorrow", [0.2]),
        },
    )
    hits = store.keyword_search("landlord Bob", limit=5)
    assert len(hits) == 1
    assert hits[0].file == "a.md"
    assert hits[0].citation() == "a.md:1-2"


# ------------------------------------------------------------------ indexing
def test_ensure_indexes_incrementally(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    (ws / "notes.md").write_text("the landlord is Bob Li\nrent is due friday\n")
    assert asyncio.run(index.ensure())
    assert store.count_chunks("fake-test") > 0
    assert set(store.files()) == {"notes.md"}
    calls_after_first = len(index.embedder.calls)

    # unchanged file: no re-embedding
    assert asyncio.run(index.ensure())
    assert len(index.embedder.calls) == calls_after_first

    # changed file: re-embedded
    (ws / "notes.md").write_text("the landlord is Bob Li\nrent is due monday\n")
    assert asyncio.run(index.ensure())
    assert len(index.embedder.calls) > calls_after_first

    # deleted file: dropped
    (ws / "notes.md").unlink()
    assert asyncio.run(index.ensure())
    assert store.files() == {}
    assert store.count_chunks("fake-test") == 0


def test_search_returns_cited_chunks(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    (ws / "notes.md").write_text(
        "grocery list:\n- milk\n- eggs\n\nlandlord: Bob Li, rent due friday\n"
    )
    chunks = asyncio.run(index.search("who is the landlord", limit=3))
    assert chunks, "expected at least one chunk"
    assert all(isinstance(c, DocChunk) for c in chunks)
    assert all(c.file == "notes.md" and c.line_start >= 1 for c in chunks)


def test_search_path_scope(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    (ws / "a.md").write_text("alpha beta gamma delta")
    (ws / "sub").mkdir()
    (ws / "sub" / "b.md").write_text("alpha beta gamma delta")
    chunks = asyncio.run(index.search("alpha beta", limit=10, path="sub"))
    assert chunks
    assert all(c.file.startswith("sub/") for c in chunks)


def test_search_without_embedder_falls_back_to_keyword(tmp_path: Path):
    ws = tmp_path / "ws"
    ws.mkdir()
    store = DocumentStore(tmp_path / "d.db")
    index = DocumentIndex(store, None, ws)  # embeddings off
    store.index = index
    (ws / "notes.md").write_text("the landlord is Bob Li")
    # ensure() is False without an embedder; search falls back to keyword
    assert asyncio.run(index.ensure()) is False
    chunks = asyncio.run(index.search("landlord", limit=5))
    assert any("landlord" in c.text for c in chunks)


# ------------------------------------------------------------------ the tool
def test_tool_is_safe_and_scoped(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    tool = DocumentsSearch(documents=store, workspace=ws)
    assert tool.name == "documents_search"
    assert tool.risk == RiskLevel.SAFE
    a = tool.assess({"query": "landlord"})
    assert "landlord" in a.summary


def test_tool_rejects_path_escape(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    tool = DocumentsSearch(documents=store, workspace=ws)
    result = asyncio.run(tool.execute(query="x", path="../outside"))
    assert not result.ok and "workspace" in (result.error or "")


def test_tool_output_has_citations(tmp_path: Path):
    store, index, ws = make_index(tmp_path)
    (ws / "notes.md").write_text("line one\nline two landlord Bob\nline three\n")
    tool = DocumentsSearch(documents=store, workspace=ws)
    result = asyncio.run(tool.execute(query="landlord", limit=3))
    assert result.ok, result.error
    assert "notes.md:" in result.output  # file:line citation present


def test_tool_without_index_reports_gracefully(tmp_path: Path):
    store = DocumentStore(tmp_path / "d.db")  # index never attached
    tool = DocumentsSearch(documents=store, workspace=tmp_path)
    result = asyncio.run(tool.execute(query="anything"))
    assert result.ok
    assert "unavailable" in result.output
