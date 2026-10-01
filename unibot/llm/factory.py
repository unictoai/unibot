"""Build the configured LLM."""

from __future__ import annotations

from unibot.config import LLMSettings
from unibot.llm.base import BaseLLM
from unibot.llm.openai_chat import OpenAIChatLLM
from unibot.llm.openai_responses import OpenAIResponsesLLM
from unibot.llm.prompt_tools import PromptToolAdapter


def create_llm(settings: LLMSettings) -> BaseLLM:
    if settings.provider == "openai":
        llm: BaseLLM = OpenAIChatLLM(settings)
    elif settings.provider == "openai_responses":
        llm = OpenAIResponsesLLM(settings)
    else:  # pragma: no cover - guarded by pydantic Literal
        raise ValueError(f"unknown llm provider: {settings.provider}")
    if settings.tool_mode == "prompt":
        llm = PromptToolAdapter(llm)
    elif settings.tool_mode == "auto":
        llm = PromptToolAdapter(llm, native_first=True)
    return llm


__all__ = ["create_llm"]
