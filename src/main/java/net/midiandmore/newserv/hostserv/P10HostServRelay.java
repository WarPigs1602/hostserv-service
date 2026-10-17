package net.midiandmore.newserv.hostserv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class P10HostServRelay {
    private static final Logger LOG = Logger.getLogger(P10HostServRelay.class.getName());
    private static final int FLAG_NOTICE = 0x4;
    private static final int FLAGS_PRIVILEGED = 0x20 | 0x40 | 0x200;
    private static final long VHOST_CHANGE_INTERVAL_SECONDS = 7L * 24 * 60 * 60;
    private static final String HOST_CHARACTERS = "[a-zA-Z0-9.:\\-\\/_\\´\\[\\]|]+";
    private static final String BUILD_DATE;
    static {
        String date = "unknown";
        try (InputStream in = P10HostServRelay.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                date = props.getProperty("build.date", date);
            }
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not load build.properties", ex);
        }
        BUILD_DATE = date;
    }

    private static final class NickUser {
        private final String numeric;
        private String nick;
        private String account;
        private final String uhost;
        private boolean registeredHost;

        private NickUser(String numeric, String nick, String account, String uhost, String modes) {
            this.numeric = numeric;
            this.nick = nick;
            this.account = account;
            this.uhost = uhost;
            this.registeredHost = modes.contains("x");
        }
    }

    private final JsonConfig config;
    private final boolean debug;
    private final AtomicBoolean burstComplete = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, NickUser> users = new ConcurrentHashMap<>();
    private HostServDatabase database;
    private volatile PrintWriter writer;

    P10HostServRelay(JsonConfig config) {
        this(config, false);
    }

    P10HostServRelay(JsonConfig config, boolean debug) {
        this.config = config;
        this.debug = debug;
    }

    void run() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Relay disconnected", ex);
            } finally {
                writer = null;
                burstComplete.set(false);
                users.clear();
            }
            Thread.sleep(10_000);
        }
    }

    private void connect() throws Exception {
        try (Socket socket = new Socket(config.get("host", "127.0.0.1"), config.intValue("port", 4400));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             HostServDatabase db = config.get("dbhost", "").isBlank() || config.get("db", "").isBlank()
                     ? null : new HostServDatabase(config)) {
            database = db;
            writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            send("PASS :%s", config.get("password", ""));
            send("SERVER %s 2 %d %d J10 %s]]] +hs6n :%s", config.get("servername", "hostserv.midiandmore.net"), now(), now(),
                    config.get("numeric", "SH"), config.get("description", "Host service"));
            send("%s N %s 2 %d %s %s +oikr - %s U]AEB %sAAA :%s",
                    numeric(), config.get("nick", "H"), now(), config.get("identd", "hostserv"),
                    config.get("servername", "hostserv.midiandmore.net"), config.get("account", "H"),
                    numeric(), config.get("description", "Host service"));
            send("%s EB", numeric());
            String line;
            while ((line = reader.readLine()) != null) {
                debugLine("<<", line);
                if (handlePingPong(line)) {
                    continue;
                }
                handle(line);
            }
        } finally {
            database = null;
        }
    }

    private void handle(String line) {
        if (isEndOfBurst(line)) {
            if (burstComplete.compareAndSet(false, true)) {
                send("%s EA", numeric());
            }
            return;
        }
        String[] tokens = splitIrcLine(stripTags(line.trim()));
        if (tokens.length < 2) {
            return;
        }
        switch (tokens[1].toUpperCase(Locale.ROOT)) {
            case "N" -> handleNick(tokens);
            case "AC" -> {
                if (tokens.length > 3) {
                    NickUser user = users.get(tokens[2]);
                    if (user != null) {
                        user.account = tokens[3];
                        applyHost(user);
                    }
                }
            }
            case "M" -> handleModes(tokens);
            case "Q" -> users.remove(tokens[0]);
            case "D" -> {
                if (tokens.length > 2) {
                    users.remove(tokens[2]);
                }
            }
            case "P" -> {
                if (tokens.length > 3 && tokens[2].equals(numeric() + "AAA")) {
                    handleCommand(tokens);
                }
            }
            default -> {
            }
        }
    }

    private void handleNick(String[] tokens) {
        if (tokens.length == 4) {
            users.computeIfPresent(tokens[0], (numeric, user) -> {
                user.nick = tokens[2];
                applyHost(user);
                return user;
            });
            return;
        }
        if (tokens.length < 10 || tokens[2].isBlank() || tokens[tokens.length - 2].isBlank()) {
            return;
        }
        String modes = tokens[7];
        String account = null;
        if (modes.contains("h") && modes.contains("z") && modes.contains("r")) {
            account = accountAt(tokens, 6);
        } else if ((modes.contains("h") && modes.contains("r")) || (modes.contains("z") && modes.contains("r"))) {
            account = accountAt(tokens, 5);
        } else if (modes.contains("r")) {
            account = accountAt(tokens, 4);
        }
        NickUser user = new NickUser(tokens[tokens.length - 2], tokens[2], account == null ? "" : account,
                tokens[5], modes);
        users.put(tokens[tokens.length - 2], user);
        applyHost(user);
    }

    private void handleModes(String[] tokens) {
        if (tokens.length < 4) {
            return;
        }
        NickUser user = users.get(tokens[0]);
        if (user == null) {
            return;
        }
        boolean adding = true;
        boolean registeredHostChanged = false;
        for (char mode : tokens[3].toCharArray()) {
            if (mode == '+') {
                adding = true;
            } else if (mode == '-') {
                adding = false;
            } else if (mode == 'x') {
                if (user.registeredHost != adding) {
                    user.registeredHost = adding;
                    registeredHostChanged = true;
                }
            }
        }
        if (registeredHostChanged) {
            applyHost(user);
        }
    }

    private void applyHost(NickUser user) {
        if (user.nick.isBlank() || user.uhost.isBlank()) {
            return;
        }
        String uhost = user.uhost;
        String vhost = null;
        if (database != null && !user.account.isBlank() && database.isRegistered(user.account)) {
            String configuredHost = database.host(user.account);
            if (configuredHost != null) {
                String[] parts = configuredHost.split("@", 2);
                if (parts.length == 2) {
                    uhost = parts[0];
                    if (!user.registeredHost) {
                        vhost = parts[1];
                    }
                }
            }
        }
        if (user.registeredHost) {
            String account = user.account.isBlank() ? user.nick : user.account;
            vhost = account + config.get("reg_host", ".users.midiandmore.net");
        }
        if (vhost == null) {
            return;
        }
        if (!isSingleParameter(vhost)) {
            LOG.log(Level.WARNING, "Configured registered host is not a valid protocol parameter");
            return;
        }
        send("%s SH %s %s %s", numeric(), user.numeric, uhost, vhost);
    }

    private boolean isSingleParameter(String value) {
        return !value.isBlank()
                && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private String accountAt(String[] tokens, int fromEnd) {
        return tokens.length > 10 && tokens[tokens.length - fromEnd].contains(":")
                ? tokens[tokens.length - fromEnd].split(":", 2)[0] : "";
    }

    private int flags(NickUser user) {
        return database == null || user.account.isBlank() ? 0 : database.flags(user.account);
    }

    private boolean privileged(NickUser user) {
        return (flags(user) & FLAGS_PRIVILEGED) != 0;
    }

    private void handleCommand(String[] tokens) {
        String source = tokens[0];
        NickUser user = users.get(source);
        if (!burstComplete.get() || user == null) {
            return;
        }
        String[] args = tokens[3].trim().split(" +");
        if (args.length == 0 || args[0].isBlank()) {
            return;
        }
        int flags = flags(user);
        boolean privileged = (flags & FLAGS_PRIVILEGED) != 0;
        String replyType = (flags & FLAG_NOTICE) != 0 ? "O" : "P";
        String command = args[0].toUpperCase(Locale.ROOT);
        if ("VERSION".equals(command)) {
            reply(source, replyType, "HostServ service 1.0-SNAPSHOT (MidiAndMore.Net) Build: " + BUILD_DATE);
        } else if ("HELP".equals(command) && args.length == 2) {
            String helpCommand = args[1].toUpperCase(Locale.ROOT);
            if ("VHOST".equals(helpCommand)) {
                reply(source, replyType, "VHOST <ident> <host> - Set your VHost on an authenticated account.");
            } else if ("UHOST".equals(helpCommand) && privileged) {
                reply(source, replyType, "UHOST <nick> <ident> <host> - Set another user's VHost.");
            } else if ("WHOAMI".equals(helpCommand) && privileged) {
                reply(source, replyType, "WHOAMI - Show your nick and account.");
            } else {
                reply(source, replyType, "Unknown command, or access denied.");
            }
        } else if ("HELP".equals(command) || "SHOWCOMMANDS".equals(command)) {
            reply(source, replyType, "Public commands:");
            reply(source, replyType, "HELP [command] - Show available commands or usage for a command.");
            reply(source, replyType, "SHOWCOMMANDS - List available commands with descriptions.");
            reply(source, replyType, "VERSION - Show the service version and build date/time (UTC).");
            reply(source, replyType, "VHOST <ident> <host> - Set your VHost.");
            if (privileged) {
                reply(source, replyType, "Privileged commands:");
                reply(source, replyType, "WHOAMI - Show your nick and account.");
                reply(source, replyType, "UHOST <nick> <ident> <host> - Set another user's VHost.");
            }
        } else if ("WHOAMI".equals(command) && privileged) {
            reply(source, replyType, "Nick: " + user.nick + ", account: " + user.account);
        } else if ("VHOST".equals(command)) {
            setOwnVHost(source, user, replyType, args);
        } else if ("UHOST".equals(command)) {
            setUserVHost(source, user, replyType, args, privileged);
        } else {
            reply(source, replyType, "Unknown command, or access denied.");
        }
    }

    private void setOwnVHost(String source, NickUser user, String replyType, String[] args) {
        if (args.length < 3) {
            reply(source, replyType, "Usage: VHOST <ident> <host>");
            return;
        }
        if (database == null || user.account.isBlank() || !database.isRegistered(user.account)) {
            reply(source, replyType, "You must be authenticated to a registered account to set a VHost.");
            return;
        }
        long lastChange = database.hostTimestamp(user.account);
        if (!privileged(user) && lastChange > 0
                && now() - lastChange < VHOST_CHANGE_INTERVAL_SECONDS) {
            reply(source, replyType, "You can only change your VHost once every seven days.");
            return;
        }
        String validationError = validateVHost(args[1], args[2]);
        if (validationError != null) {
            reply(source, replyType, validationError);
            return;
        }
        database.saveHost(user.account, args[1], args[2]);
        applyCustomVHost(user, args[1], args[2]);
        reply(source, replyType, args[1] + "@" + args[2] + " is now your VHost.");
    }

    private void setUserVHost(String source, NickUser actor, String replyType, String[] args,
            boolean isPrivileged) {
        if (!isPrivileged || actor.account.isBlank()) {
            reply(source, replyType, "Unknown command, or access denied.");
            return;
        }
        if (args.length < 4) {
            reply(source, replyType, "Usage: UHOST <nick> <ident> <host>");
            return;
        }
        String targetNumeric = findNumericByNick(args[1]);
        NickUser target = targetNumeric == null ? null : users.get(targetNumeric);
        if (target == null) {
            reply(source, replyType, "User not found.");
            return;
        }
        String validationError = validateVHost(args[2], args[3]);
        if (validationError != null) {
            reply(source, replyType, validationError);
            return;
        }
        if (!target.account.isBlank() && database != null && database.isRegistered(target.account)) {
            database.saveHost(target.account, args[2], args[3]);
        }
        applyCustomVHost(target, args[2], args[3]);
        reply(source, replyType, target.nick + " has now the VHost: " + args[2] + "@" + args[3]);
        String targetReplyType = (flags(target) & FLAG_NOTICE) != 0 ? "O" : "P";
        reply(targetNumeric, targetReplyType, actor.nick + " changed your VHost to " + args[2] + "@" + args[3]);
    }

    private String findNumericByNick(String nick) {
        return users.entrySet().stream()
                .filter(entry -> entry.getValue().nick.equalsIgnoreCase(nick))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void applyCustomVHost(NickUser user, String uhost, String vhost) {
        if (!user.nick.isBlank()) {
            send("%s SH %s %s %s", numeric(), user.numeric, uhost, vhost);
        }
    }

    private String validateVHost(String uhost, String vhost) {
        if (uhost.length() > 10 || vhost.length() > 63) {
            return "The VHost is too long.";
        }
        if (uhost.isEmpty() || vhost.isEmpty()) {
            return "The VHost is too short.";
        }
        if (!uhost.matches(HOST_CHARACTERS) || !vhost.matches(HOST_CHARACTERS)) {
            return "The VHost contains invalid characters.";
        }
        return null;
    }

    private void reply(String source, String type, String message) {
        send("%sAAA %s %s :%s", numeric(), type, source, message);
    }

    private String stripTags(String line) {
        if (!line.startsWith("@")) {
            return line;
        }
        int tagsEnd = line.indexOf(' ');
        return tagsEnd < 0 ? "" : line.substring(tagsEnd + 1).trim();
    }

    private String[] splitIrcLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inTrailing = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inTrailing) {
                current.append(c);
            } else if (c == ' ') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else if (c == ':' && current.length() == 0) {
                if (i != 0) {
                    inTrailing = true;
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    private boolean handlePingPong(String line) {
        String candidate = stripTags(line.trim());
        if (candidate.isEmpty()) {
            return false;
        }
        if (candidate.startsWith(":")) {
            int prefixEnd = candidate.indexOf(' ');
            if (prefixEnd >= 0) {
                candidate = candidate.substring(prefixEnd + 1).trim();
            }
        }
        String[] tokens = splitIrcLine(candidate);
        if (tokens.length == 0) {
            return false;
        }
        int commandIndex = ("G".equalsIgnoreCase(tokens[0]) || "PING".equalsIgnoreCase(tokens[0])) ? 0 : 1;
        if (commandIndex >= tokens.length || !("G".equalsIgnoreCase(tokens[commandIndex])
                || "PING".equalsIgnoreCase(tokens[commandIndex]))) {
            return false;
        }
        String fallback = config.get("servername", "hostserv");
        String target = commandIndex + 1 < tokens.length ? tokens[commandIndex + 1] : fallback;
        String payload = commandIndex + 2 < tokens.length ? tokens[commandIndex + 2] : target;
        if (target.startsWith(":")) {
            target = target.substring(1);
        }
        if (payload.startsWith(":")) {
            payload = payload.substring(1);
        }
        if (target.isBlank()) {
            target = fallback;
        }
        if (payload.isBlank()) {
            payload = target;
        }
        send("%s Z %s :%s", numeric(), target, payload);
        return true;
    }

    private boolean isEndOfBurst(String line) {
        String[] tokens = splitIrcLine(stripTags(line.trim()));
        return tokens.length > 0 && ("EB".equalsIgnoreCase(tokens[0])
                || tokens.length > 1 && "EB".equalsIgnoreCase(tokens[1]));
    }

    private void send(String format, Object... args) {
        PrintWriter output = writer;
        if (output != null) {
            String line = format.formatted(args);
            debugLine(">>", line);
            output.println(line);
        }
    }

    private void debugLine(String direction, String line) {
        if (debug) {
            System.out.println(direction + " " + redact(line));
        }
    }

    static String redact(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PASS ")) {
            return "PASS <redacted>";
        }
        int password = upper.indexOf(" PASS ");
        if (password >= 0) {
            return line.substring(0, password) + " PASS <redacted>";
        }
        int auth = upper.indexOf(":AUTH ");
        if (auth < 0) {
            auth = upper.indexOf(" AUTH ");
        }
        return auth < 0 ? line : line.substring(0, auth) + line.substring(auth, auth + 5) + " <redacted>";
    }

    private String numeric() {
        return config.get("numeric", "SH");
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }
}
