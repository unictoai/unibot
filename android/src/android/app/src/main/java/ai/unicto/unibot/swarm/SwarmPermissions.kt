package ai.unicto.unibot.swarm

import kotlinx.serialization.Serializable

/**
 * OpenDots-style per-role permissions for a swarm run.
 *
 * Each crew role (planner, researcher, writer, editor, analyst, verifier)
 * gets two independent switches, set in the mission composer and stored on
 * the run (launch options -> UI state -> checkpoint) so a resumed run keeps
 * exactly the permissions it started with.
 */
@Serializable
data class SwarmRolePermission(
    val roleId: String,
    /**
     * Whether this role may call tools. The engine enforces this at the
     * single provider-call choke point: a role with tools disabled always
     * gets an empty tool list, so its model can only reason. Defaults to
     * false, matching the current reasoning-only workers.
     */
    val toolsEnabled: Boolean = false,
    /**
     * Whether this role's risky tool calls raise an approval prompt. When
     * false, risky calls are denied outright instead of prompting — see
     * [SwarmEngine.shouldPromptForRiskyCall]. Defaults to true (prompt),
     * the safe option.
     */
    val askBeforeRisky: Boolean = true,
)

/**
 * Permission lookup with safe defaults: a role with no explicit entry
 * behaves as [SwarmRolePermission] defaults (no tools, prompt on risky).
 * Unknown/future role ids never crash the lookup.
 */
fun List<SwarmRolePermission>.forRole(roleId: String): SwarmRolePermission =
    firstOrNull { it.roleId == roleId } ?: SwarmRolePermission(roleId)
