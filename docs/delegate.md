# Delegating to worker sub-agents

unibot is a multi-agent system: the main agent can spawn specialist **worker**
sub-agents for self-contained pieces of work, using the `delegate` tool.

## How it works

Ask the agent to do two independent things at once — or tell it to delegate
explicitly — and it can hand a well-specified subtask to a worker:

- The worker runs the **same model and provider** as your current session, in
  a **fresh context window** with its own agentic loop.
- The worker gets file, shell and browser tools. It does **not** get
  `delegate` (workers can't spawn workers) and it can't write to your
  long-term memory — everything it learns comes back in its summary.
- The worker **cannot ask you questions**. The main agent must give it the
  full task, constraints and background up front.
- The worker's tool calls appear in the chat as normal tool pills, so you can
  watch what it's doing.

## Safety

Delegation grants a **fresh context, not extra privilege**. Every worker tool
call runs through the same execution path as the main agent's:

- Approval cards still appear for irreversible actions (deleting files,
  sending data out, paying).
- The worker can never type passwords, verification codes or card numbers —
  those screens are handed to you.
- A worker that goes off-track is bounded: its loop caps at 8 turns by
  default (max 15), and each tool result is truncated before being fed back.

Hitting **Stop** cancels a running worker along with the turn.

## Tips

- Give the worker a crisp task: goal, constraints, and exactly what the
  summary must contain. "Research X and report back with prices and links"
  beats "look into X".
- Use delegation for **independent** work. If step B needs step A's result,
  let the main agent do them in order instead.
- Workers are best for research, file scavenging, and parallel
  investigations — not for actions you'd want to approve one by one, since
  each approval still interrupts you.
