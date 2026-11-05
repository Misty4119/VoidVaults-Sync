# VoidVaults

VoidVaults replaces Ender Chest access with configurable personal vaults. SIMPLE mode provides one vault with permission-based size; PAGED mode adds multiple pages and utility functions. Optional Vault and PlaceholderAPI integrations are declared in the plugin descriptor.


## Current version and tested compatibility

**Current deliverable version:** `2.8.0`.

**Target:** Minecraft 26.2–26.3 on Paper, Folia, and Canvas.

**Tested builds:** Paper 26.2 #135 and 26.3 #242; Folia 26.2 #7, 26.3 #3 (required), and #6; Canvas 26.2 #994 and 26.3 #1009 (latest successful 26.3 build checked 2026-11-05). **Verification scope:** Startup, plugin enablement, and general console smoke only; player workflows, data migrations, optional integrations, and cross-server behavior were not tested.

## Build and install

Use Java 25 and Maven: `mvn test package`. Install the shaded plugin JAR from `target/` in the server's `plugins/` directory. Restart once to create configuration, then edit `plugins/VoidVaults/config.yml` and restart after selecting storage and credentials.

## Storage

| Mode | Storage behavior |
| --- | --- |
| `YAML` | Local file storage for one server. |
| `MYSQL` | Shared SQL storage. |
| `REDIS` | Redis primary storage without a MySQL backup. |
| `REDIS_PERSISTENT` | Redis primary storage with MySQL persistence. |

The bundled configuration defaults to `REDIS_PERSISTENT`; configure both services before deployment, or explicitly choose an appropriate alternative. Give each network node a unique Redis `server-id` and consistent key/channel prefixes. Back up player data before changing storage modes or upgrading shared nodes. Test upgrades on a staging copy and keep credentials in server-local configuration.

See the bundled `config.yml` for TLS, Redis ACL, pool, cache, autosave, cooldown, and paging settings. Use the artifact built from your intended revision, and never resolve a failed upgrade by deleting player tables or Redis data.

## Commands and permissions

- `/echest [page]` (aliases `/pv`, `/vault`) opens remote access with `voidvaults.remote`.
- `/voidvaults open|reload|setslots|setpages` (aliases `/vvs`, `/vv`) provides administration. The descriptor defines `voidvaults.admin.openother`, `voidvaults.admin.reload`, `voidvaults.admin.setslots`, and `voidvaults.admin.setpages`.
- `voidvaults.slots.<size>` and `voidvaults.page.<number>` control vault capacity and pages. The available values and defaults are defined in `src/main/resources/plugin.yml`.

## Compatibility

The current source version is **2.8.0** (`pom.xml`). Paper, Folia, and Canvas on Minecraft 26.2–26.3 are target platforms.

The Maven dependency and plugin descriptor define the intended API baseline. Verify save/reopen, full inventories, disconnects, simultaneous access, backend failures, and restart recovery with backed-up staging data before deploying to a shared network.

## License

[MIT License](LICENSE), retaining the original copyright and attribution.
