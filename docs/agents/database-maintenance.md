# Database maintenance

Read this before touching `services.database.H2Maintenance`, `CredentialCopies`, `H2TraceMask`, `utils.CredentialCipher` or a credential column, or `jclaw.sh`'s `backup`, `restore`, `repair`, `compact` or `db-*` commands.

- `utils.CredentialCipher` (JCLAW-1455) encrypts these at rest: the Config keys `ConfigService.encryptsAtRest` flags (outside `auth.`, a key whose last segment's last word ends in `key`, `keys` or `token`, or that has a word containing `secret` or `password`), the Slack, Telegram and WhatsApp binding token and secret columns, and the secret values in `McpServer` and `ChannelConfig` JSON.

- Database backup, restore, repair and compaction are one engine, `services.database.H2Maintenance`, behind two doors: the JVM (the Settings panel and `/api/system/database`, which `jclaw.sh backup|db-clean|db-status` also call while the app is up) and `jclaw.sh`'s out-of-process run of its `main` on the H2 and Gson jars plus the compiled classes, which `restore`, `repair` and `compact` always use, stopping and restarting a running instance. Compaction never runs at shutdown, where `jclaw.sh stop`'s kill after about 95 s could cut it short. Keep it, `CredentialCopies` and `H2TraceMask` on the JDK, H2 and Gson only — `H2MaintenanceTest.theEngineStandsAlone` pins that — and never let them read the clock: callers pass the stamp.
