# Prompts for Claude Code

## First session (paste once)

You're joining an existing project. CLAUDE.md has the rules; follow them.

1. Read docs/PROGRESS.md, then the "Modules" table and the "Phase 1" section of docs/ROADMAP.md. Nothing else yet.
2. Run `./mvnw -q verify` and tell me in 3 lines or fewer whether Phase 0 is green.
3. Give me a Phase 1 plan: ordered steps, the classes and tests per step, and anything in the roadmap you think is wrong or missing. Keep it under 40 lines.
4. Stop and wait for my OK before writing code.

## Start of each later session

Read docs/PROGRESS.md and only the current phase in docs/ROADMAP.md. Continue from "Next". Plan first, wait for my OK.

## End of each phase

Phase N is code-complete. Now:
1. Write docs/lessons/phase-N.md (concepts, code walkthrough, 4–6 exercises, key terms).
2. Draft docs/linkedin/phase-N.md.
3. Update docs/PROGRESS.md.
Then walk me through the lesson's main ideas in under 15 lines.

## Reviewing my exercises

Review my solution to exercise X in docs/lessons/phase-N.md. Point out mistakes and hint at fixes; don't rewrite it for me.
