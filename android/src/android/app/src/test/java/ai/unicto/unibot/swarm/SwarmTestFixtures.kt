package ai.unicto.unibot.swarm

import ai.unicto.unibot.data.model.AgentToolDefinition
import ai.unicto.unibot.data.model.LLMMessage
import ai.unicto.unibot.data.model.LLMModel
import ai.unicto.unibot.data.model.LLMResponse
import ai.unicto.unibot.data.model.LLMStreamChunk
import ai.unicto.unibot.data.model.LLMUsage
import ai.unicto.unibot.data.model.ThinkingLevel
import ai.unicto.unibot.provider.LLMProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted response for one fake LLM call. */
data class FakeSwarmResponse(
    val text: String,
    val promptTokens: Int = 100,
    val completionTokens: Int = 50,
)

/**
 * Hermetic fake [LLMProvider] for swarm tests: scripted per-call responses,
 * recorded calls, and an optional gate that blocks a worker call for one
 * role (used to test pause-mid-run deterministically).
 */
class FakeSwarmProvider(
    private val handler: (system: String?, user: String) -> FakeSwarmResponse,
    private val blockRole: String? = null,
    private val gate: CompletableDeferred<Unit>? = null,
) : LLMProvider {
    override val name: String = "fake-swarm"
    override var model: LLMModel = LLMModel(
        id = "fake-model",
        displayName = "Fake",
        provider = "fake",
        supportsReasoning = false,
    )

    /** (systemPrompt, userMessage) per call, in order. Guarded for IO-thread appends. */
    val calls = mutableListOf<Pair<String?, String>>()

    fun workerCalls(roleId: String): Int = synchronized(calls) {
        calls.count { (_, user) ->
            user.startsWith("WORKER SUBTASK") && user.contains("($roleId)")
        }
    }

    fun callsStartingWith(prefix: String): Int = synchronized(calls) {
        calls.count { (_, user) -> user.startsWith(prefix) }
    }

    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = throw UnsupportedOperationException("fake-swarm is streaming-only")

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = flow {
        val user = messages.lastOrNull { it.role == LLMMessage.Role.USER }?.content.orEmpty()
        synchronized(calls) { calls.add(systemPrompt to user) }
        if (blockRole != null && user.startsWith("WORKER SUBTASK") && user.contains("($blockRole)")) {
            gate?.await()
        }
        val r = handler(systemPrompt, user)
        emit(LLMStreamChunk.Started)
        if (r.text.isNotEmpty()) emit(LLMStreamChunk.Text(r.text))
        emit(
            LLMStreamChunk.Usage(
                LLMUsage(inputTokens = r.promptTokens, outputTokens = r.completionTokens),
            ),
        )
        emit(LLMStreamChunk.Finished("stop"))
    }
}

/** In-memory [SwarmCheckpointStore] — stands in for SharedPreferences in JVM tests. */
class InMemorySwarmCheckpointStore : SwarmCheckpointStore {
    var json: String? = null
        private set

    override fun save(json: String) {
        this.json = json
    }

    override fun load(): String? = json

    override fun clear() {
        json = null
    }
}

/** Extracts the role id from a "Role: X (roleId)" worker prompt line. */
fun roleOfWorkerPrompt(user: String): String =
    Regex("""\((\w+)\)""").find(user)?.groupValues?.get(1).orEmpty()

/**
 * Standard scripted handler for the `research` preset ([researcher, verifier]):
 * well-formed 2-item plan, per-role worker output, configurable verifier.
 */
fun standardSwarmHandler(
    plan: List<String>,
    verify: (output: String) -> String = { "PASS" },
): (String?, String) -> FakeSwarmResponse = { _, user ->
    when {
        user.startsWith("MISSION DECOMPOSITION") -> FakeSwarmResponse(
            plan.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
        )
        user.startsWith("WORKER SUBTASK") ->
            FakeSwarmResponse("Result from ${roleOfWorkerPrompt(user)}")
        user.startsWith("VERIFY OUTPUT") -> {
            val output = user.substringAfter("Worker output:").trim()
            FakeSwarmResponse(verify(output))
        }
        user.startsWith("STITCH RESULTS") -> FakeSwarmResponse("Stitched document")
        else -> FakeSwarmResponse("unexpected call")
    }
}
