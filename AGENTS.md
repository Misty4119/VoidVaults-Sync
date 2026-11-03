# VoidVaults contributor guide

## Project map and contracts

- `src/main/java/com/voidvault` separates commands, GUI listeners, managers, YAML/MySQL storage, Redis storage, and scheduling utilities. Read `src/main/resources/config.yml` before changing storage modes or network assumptions.
- Vault contents are durable player assets. Preserve locking, version/conflict checks, save completion/error propagation, dirty cache flushing, close/disconnect handling, and serialization compatibility. Never report a conflicting or failed write as successful.
- YAML, MYSQL, REDIS, and REDIS_PERSISTENT have different persistence contracts. Redis-primary mode must not be described as database-only invalidation. Review the storage implementation and `docs/research/database-sync-security.md` before changing synchronization.
- Use `SchedulerUtil` for live player/inventory/entity operations; keep SQL, Redis, and file I/O asynchronous. Recheck session ownership before applying a delayed callback.
- Never repair an existing schema by dropping player tables. Follow the current backup and refusal guidance under `docs/`; treat version-specific rollout examples as historical unless verified for the artifact being deployed.
- Read `pom.xml` for Java/toolchain/dependencies. `mvn test package` verifies and creates the shaded plugin under `target/`. Preserve MIT attribution and license wording.

## Repository hygiene and verification

- Read the build manifest and `src/main/resources/plugin.yml` (or the owning module's equivalent) before changing API, Java, command, permission, or dependency assumptions. Keep commands, defaults, and examples in the README aligned with those files.
- Keep source resources, sanitized fixtures, wrapper files, and license notices in version control. Keep build output, IDE state, test servers, player data, database dumps, credentials, and private notes out of commits. `.gitignore` does not remove files already tracked; inspect staged changes for secrets.
- Preserve upstream author credits, provenance, trademarks, and license text. Do not present this checkout's changes as an upstream release.
- Select verification for the changed behavior and report exactly what ran. Documentation-only edits can be reviewed with a diff and path/config checks; they do not require a server launch. Never start servers, publish artifacts, migrate shared databases, or upload releases as an incidental verification step.
- Dependency coordinates and `folia-supported`/`canvas-supported` flags express build targets and declarations. They do not prove runtime compatibility. A release claim needs evidence tied to the artifact, exact server build, Java runtime, and exercised workflows; keep incomplete platform/version checks pending.
