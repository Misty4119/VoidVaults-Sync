# VoidVaults

VoidVaults 以可設定的個人儲物庫取代終界箱存取。SIMPLE 模式提供一個依權限決定容量的儲物庫；PAGED 模式則增加多個分頁與實用功能。外掛描述檔宣告了選用的 Vault 與 PlaceholderAPI 整合。


## 目前版本與實測相容性

**目前輸出版本：** `2.8.0`。

**目標：** Minecraft 26.2–26.3，使用 Paper、Folia 與 Canvas。

**已測試建置：** Paper 26.2 #135 與 26.3 #242；Folia 26.2 #7、26.3 #3（指定 build）與 #6；Canvas 26.2 #994 與 26.3 #1009（2026-11-05 查得最新成功的 26.3 build）。**驗證範圍：**僅啟動、外掛載入與一般主控台 smoke test；未測試玩家流程、資料遷移、選用整合或跨伺服器行為。

## 建置與安裝

使用 Java 25 與 Maven：`mvn test package`。將 `target/` 中的 shaded 外掛 JAR 安裝到伺服器的 `plugins/` 目錄。首次重新啟動會建立設定檔；接著編輯 `plugins/VoidVaults/config.yml`，選好儲存方式並設定憑證後再重新啟動。

## 儲存方式

| 模式 | 儲存行為 |
| --- | --- |
| `YAML` | 單一伺服器使用本機檔案儲存。 |
| `MYSQL` | 共用 SQL 儲存。 |
| `REDIS` | 以 Redis 為主要儲存，不使用 MySQL 備份。 |
| `REDIS_PERSISTENT` | 以 Redis 為主要儲存，並持續寫入 MySQL。 |

內附設定預設使用 `REDIS_PERSISTENT`；部署前請先設定這兩項服務，或明確選擇合適的替代方式。每個網路節點都要使用唯一的 Redis `server-id`，並確保 key/channel 前綴一致。變更儲存模式或升級共用節點前，請先備份玩家資料。在測試副本上驗證升級，並將憑證留在伺服器本機設定中。

TLS、Redis ACL、連線池、快取、自動儲存、冷卻時間與分頁設定請參閱內附的 `config.yml`。請使用由預期 revision 建置的產物；升級失敗時，絕不可藉由刪除玩家資料表或 Redis 資料來解決。

## 指令與權限

- `/echest [page]`（別名 `/pv`、`/vault`）使用 `voidvaults.remote` 開啟遠端存取。
- `/voidvaults open|reload|setslots|setpages`（別名 `/vvs`、`/vv`）提供管理功能。描述檔定義了 `voidvaults.admin.openother`、`voidvaults.admin.reload`、`voidvaults.admin.setslots` 與 `voidvaults.admin.setpages`。
- `voidvaults.slots.<size>` 與 `voidvaults.page.<number>` 控制儲物庫容量與分頁。可用值與預設值定義於 `src/main/resources/plugin.yml`。

## 相容性

目前原始碼版本為 **2.8.0**（`pom.xml`）。目標平台為 Minecraft 26.2–26.3 上的 Paper、Folia 與 Canvas。

Maven 相依項目與外掛描述檔定義了預期的 API 基準。部署到共用網路前，請在已備份的測試環境驗證儲存後重新開啟、滿載物品欄、玩家離線、同時存取、後端故障及重新啟動後的復原情況。

## 授權

[MIT License](LICENSE)；保留原始著作權聲明與姓名標示。
