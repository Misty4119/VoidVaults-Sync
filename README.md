# VoidVaults

> **Current test status (2026-11-03):** PlugDev completed a seven-cell startup/console smoke on Paper 26.2 #135 and 26.3 #237; Folia 26.2 #7 and 26.3 #3 (required), with #6 supplementary; and Canvas 26.2 #994 and 26.3 #993. No player-driven gameplay, GUI, cross-node, or packet-level suite ran. The harness does not support valid Folia plugin-status probes, so Folia evidence is limited to startup/console logs. These targets do not establish feature compatibility.


VoidVaults replaces Ender Chest access with configurable personal vaults. SIMPLE mode provides one vault with permission-based size; PAGED mode adds multiple pages and utility functions. Optional Vault and PlaceholderAPI integrations are declared in the plugin descriptor.

## Build and install

Use Java 25 and Maven: `mvn test package`. Install the shaded plugin JAR from `target/` in the server's `plugins/` directory. Restart once to create configuration, then edit `plugins/VoidVaults/config.yml` and restart after selecting storage and credentials.

## Storage

| Mode | Storage behavior |
| --- | --- |
| `YAML` | Local file storage for one server. |
| `MYSQL` | Shared SQL storage. |
| `REDIS` | Redis primary storage without a MySQL backup. |
| `REDIS_PERSISTENT` | Redis primary storage with MySQL persistence. |

The bundled configuration defaults to `REDIS_PERSISTENT`; configure both services before deployment, or explicitly choose an appropriate alternative. Give each network node a unique Redis `server-id` and consistent key/channel prefixes. Back up player data before changing storage modes or upgrading shared nodes. Credentials belong in server-local configuration.

See the bundled `config.yml` for TLS, Redis ACL, pool, cache, autosave, cooldown, and paging settings. [Historical rollout guidance](docs/2.5.0-safe-rollout.md) explains backup and non-destructive schema recovery principles; use the artifact built from your intended revision.

## Commands and permissions

- `/echest [page]` (aliases `/pv`, `/vault`) opens remote access with `voidvaults.remote`.
- `/voidvaults open|reload|setslots|setpages` (aliases `/vvs`, `/vv`) provides administration. The descriptor defines `voidvaults.admin.openother`, `voidvaults.admin.reload`, `voidvaults.admin.setslots`, and `voidvaults.admin.setpages`.
- `voidvaults.slots.<size>` and `voidvaults.page.<number>` control vault capacity and pages. The available values and defaults are defined in `src/main/resources/plugin.yml`.

## Compatibility

This checkout targets modern Paper-family APIs. Build dependencies and plugin support flags do not establish runtime compatibility. The latest run recorded startup/console smoke only; vault and economy synchronization behavior remain unverified.

The Maven dependency and plugin descriptor define the intended API baseline. Verify save/reopen, full inventories, disconnects, simultaneous access, backend failures, and restart recovery with backed-up staging data before deploying to a shared network.

## License

[MIT License](LICENSE), retaining the original copyright and attribution.
