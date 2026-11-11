# Task 5.5 implementer report

Status: DONE. Full backend suite: 343 tests, 0 failures, BUILD SUCCESS (no DASHSCOPE_API_KEY).
Orchestrator independently re-ran the suite: 343 tests, BUILD SUCCESS.

## Changes

- `access/RoleDatasourceService` (new): matrix / datasourcesOf / replaceDatasources; validation-first
  400s, audited 404 denials via REQUIRES_NEW, atomic delete+insert + one `role.datasources` SUCCESS
  row in a `TransactionalOperator`.
- `access/RoleDatasource` (new): `(roleId, datasourceId)` projection.
- `AccessAdminRepository`/`R2dbcAccessAdminRepository`: `datasourceIdsOf`, `datasourceGrants`,
  `replaceRoleDatasources` (bound SQL).
- DTOs: `DatasourceMatrixView`, `DatasourceMatrixRoleView`, `DatasourceMatrixDatasourceView`,
  `RoleDatasourcesView`, `RoleDatasourcesRequest`.
- `AccessControlController`: three new routes under the existing base path.
- Two repository test spies extended.
- `web/AccessControlDatasourceMatrixTest` (10 tests): matrix listing, replace vs union, empty clear,
  de-dup, 404s, missing body 400, student 403, audit transitions, next-request effect via
  `AccessContextResolver`, legacy endpoint compatibility.

## Concerns

- Matrix test asserts the datasource list exactly `[campus, erp-prod]` (test profile order).
- Registry definitions/defaultId are in-memory; called on the reactive chain like the legacy service.
