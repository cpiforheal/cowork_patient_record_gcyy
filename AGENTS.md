# AGENTS.md

@C:\Users\Administrator\.codex\RTK.md

## Coding Defaults

- For coding, code review, refactoring, debugging, or implementation tasks, apply the `karpathy-guidelines` skill by default when it is available.
- Do not spawn sub-agents by default.
- Spawn sub-agents only when explicitly requested, and keep them bounded to independent read-only exploration or clearly assigned implementation work.

## Tool Hygiene

- Do not call the top-level `wait` tool in this workspace.
- Never reuse historical exec cell ids. In particular, do not poll stale ids copied from earlier turns or summaries.
- For long-running shell commands, run them inside `exec` and poll only the returned `session_id` with `tools.write_stdin`.
- If an `exec_command` call returns no `session_id`, treat it as complete and do not poll.
- In Default mode, do not call `request_user_input`; either continue from local context or ask a plain-text blocking question.
- If any unavailable tool or stale wait id is accidentally triggered once, stop using that tool family for the rest of the turn.

## Deployment Workflow

- After each code fix requested for this project, build the changed system, sync the fresh artifacts into `release/clinic-portable`, restart the portable service, then make a `feat` commit and push it.
- Do not include unrelated dirty files in commits.
