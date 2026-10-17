# hostserv-service

Minimal P10 service skeleton for newserv, ported from spamscan-service without any spam detection.

It connects as a server, introduces a service user, tracks users and their accounts, and answers private commands. It also cloaks visible user hosts and applies the configured registered host to users with mode `+x`; removing `+x` restores the cloak. Privilege detection is kept: with a PostgreSQL database configured, `chanserv.users.flags` of the user's account decides who is privileged (`0x20 | 0x40 | 0x200`) and whether replies are sent as notice (`0x4`).

Supported commands: `HELP [command]`, `SHOWCOMMANDS`, `VERSION`, `VHOST <ident> <host>`, and the privileged `UHOST <nick> <ident> <host>` and `WHOAMI` commands. `VHOST` requires an authenticated, registered account and is limited to one change every seven days (privileged accounts are exempt). `UHOST` requires a privileged account; changes are stored when the target is authenticated to a registered account. Saved VHosts are applied when users connect or authenticate; the service creates the compatible `hostserv.hosts` table if it does not exist.

Set `reg_host` in `config.json` to the registered-host suffix (default: `.users.midiandmore.net`); the authenticated account name is prepended when a user has `+x` and has no saved VHost.

## Build and run

Requires Java 17+ and Maven.

```text
mvn clean package
java -jar target/hostserv-service-1.0-SNAPSHOT-jar-with-dependencies.jar [--debug] [--daemon start|stop] [config.json]
```

A disconnect clears the in-memory state and reconnects after 10 seconds.
