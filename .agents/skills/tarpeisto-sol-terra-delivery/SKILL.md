---
name: tarpeisto-sol-terra-delivery
description: Plan and deliver Tarpeisto application changes with a SOL planner and a Terra implementer, including review, testing, and the repository's commit-message conventions. Use for feature work, bug fixes, refactors, migrations, and implementation phases in this repository; do not use for read-only questions or status reports.
---

# Tarpeisto SOL/Terra delivery

Use this workflow for application implementation. The primary agent remains responsible for scope, review, verification, and the final handoff.

## Prepare

1. Read `AGENTS.md` and the required project documents in their prescribed order.
2. Inspect the current worktree and preserve unrelated user changes.
3. Keep all work within the authority granted by the request. Planning and delegation do not authorize unrelated changes, pushes, deployments, or history rewrites.

## Plan with SOL

Delegate planning to an available SOL-family subworker, preferring the newest suitable SOL model.

The planner must not edit files. Ask it to inspect the relevant implementation and return:

- the intended behavior and acceptance criteria;
- affected files and architectural boundaries;
- security, tenancy, migration, compatibility, and deployment risks;
- an implementation sequence; and
- focused and full-suite verification.

Resolve material conflicts between the plan and the project documents before implementation.

## Implement with Terra

After the plan is reviewed, delegate implementation to an available Terra-family subworker, preferring the newest suitable Terra model.

Give the implementer the accepted plan, relevant constraints, expected checks, and an instruction not to commit. Use one implementation subworker at a time because subworkers share the worktree. The implementer must report deviations and blockers instead of silently broadening scope.

The primary agent then reviews the complete diff, checks the documented invariants, and verifies that generated contracts or deployment examples were updated when affected.

If implementation defects remain, send a focused correction back to Terra first. When the defect requires deeper diagnosis or Terra is not sufficient, a SOL subworker may diagnose and implement the repair. Review every repair in the same way.

If either model family is unavailable, tell the user instead of silently substituting a different workflow.

## Test at the right boundary

Run focused checks while they provide useful feedback. For consecutive phases or tightly related changes, the primary agent may defer the complete backend and frontend suites until the final phase when the intermediate diff is coherent and low-risk. Do not defer checks past the requested review point, and do not report completion until all applicable checks in `docs/DEVELOPMENT_POLICIES.md` pass.

Distinguish real failures from environmental limitations. Fix product defects; report infrastructure blockers precisely.

## Commit convention

Commit only after the diff and applicable checks have been reviewed. Keep each commit to one coherent implementation unit.

Use a concise Conventional Commit subject:

```text
type(scope): imperative summary
```

Follow it with a short bullet list containing only the important outcomes. Prefer two bullets; use one to three when that better matches the change.

```text
feat(setup): add browser onboarding

- Create the organization and first Owner from the setup page
- Remove deployment seed configuration
```

Do not add prose paragraphs, large descriptions, `Co-Authored-By` trailers, or agent/model attribution. Do not amend, rebase, force-push, or otherwise rewrite existing history unless the user explicitly requests it.

## Handoff

Report the outcome first, followed by the commit hash when committed, the meaningful checks that ran, and any migration or operator action. Keep the summary concise and never claim a check that did not run.
