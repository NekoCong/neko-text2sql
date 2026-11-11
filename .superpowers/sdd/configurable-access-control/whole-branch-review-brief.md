# Whole-branch review brief — configurable-access-control

Review the complete change before deployment, not one task. The per-task reviews are in
`.superpowers/sdd/configurable-access-control/` (brief/report/review packages + ledger progress.md).

## Scope

- **Base:** `bd8de40` (last commit before the access-control work)
- **Head:** final 6.x commit
- Compare against `openspec/changes/configurable-access-control/{proposal.md,design.md,tasks.md}` and
  `specs/access-control/spec.md` (all requirements and scenarios), not just the task briefs.
- Also review the docs/deploy changes (`README.md`, `docs/nl2sql.md`, `docs/deploy.md`,
  `deploy/bootstrap.sh`, `deploy/docker-compose.prod.yml`).

## What to check

1. **Spec coverage:** walk every requirement/scenario in the spec and map it to code + a test. Flag
   anything unimplemented, untested, or contradicted. In particular:
   - roles are data; create/edit/disable/inheritance/built-in protection;
   - permission catalog + matrix; next-request effect;
   - data scopes `ALL/FILTER/DENY`, merge order `DENY > ALL > FILTER`, unconfigured deny;
   - user lifecycle (create/list/detail/display name/disable/enable/reset/roles), no hard delete,
     no password material, immediate token invalidation;
   - `user.read`/`user.manage`/`audit.read`/`access.manage` route gating;
   - audit rows (actor/time/entity/before-after/result, SUCCESS vs DENIED, no secrets, read-only API);
   - anti-escalation (permissions, parent/inheritance, user role assignment, built-in admin) and
     last-admin lockout;
   - migration/backward compatibility (old columns/tables kept, backfill, old JWTs fail closed);
   - CTE hardening (same-name CTE, schema-qualified refs, recursive CTE, DENY-only tables,
     fail-closed values).
2. **Security:** fail-closed paths, no authorization from JWT `role`, no raw SQL/client-built
   predicates, no data-scope bypass, no password/token leakage, no privilege escalation or lockout
   route left open. Pay special attention to interactions between tasks (e.g. role edit + inheritance +
   lockout + user role assignment + disabled roles).
3. **Consistency:** DTO/API shapes vs frontend types/client; H2 test seed vs MySQL seed vs migration;
   permission keys consistent across code/seed/frontend; docs match actual routes/behavior.
4. **Residual minors:** review the ledger's deferred items and decide which must be fixed before
   deploy vs. documented debt.
5. **Scope discipline:** no sandbox/container isolation, no approval workflow, no new dependencies.

## Known blockers / not verifiable locally

- MySQL migration and `app-data.sql` seed have only been exercised on H2; Docker is unavailable.
- No `DASHSCOPE_API_KEY`: real model generation and a full AgentScope E2E are not exercised.
- Frontend has no browser E2E; only build + unit tests + static review.

Mark these ⚠️ rather than treating them as failures, but check the scripts/docs are runnable/consistent.

## Output

- Findings ordered by severity (Critical / Important / Minor), each with file:line and a concrete
  fix or verification step.
- A spec coverage matrix (requirement → implementation → test) with gaps called out.
- An explicit deploy-readiness verdict: ready / ready with listed fixes / not ready.
- Do not dispatch subagents; read-only.
