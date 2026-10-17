package net.midiandmore.newserv.hostserv;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class Main {
    record Options(Path config, boolean debug, boolean daemon, boolean child, boolean stop) {
    }

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Options options = parseArgs(args);
        if (options.daemon()) {
            manageDaemon(options);
            return;
        }

        Logger log = Logger.getLogger(P10HostServRelay.class.getName());
        FileHandler errors = null;
        try {
            errors = new FileHandler("error.log", true);
            errors.setEncoding("UTF-8");
            errors.setFormatter(new SimpleFormatter());
            log.addHandler(errors);
        } catch (IOException | SecurityException ex) {
            if (errors != null) {
                errors.close();
                errors = null;
            }
            System.err.println("Could not open error.log: " + ex.getMessage());
        }
        try {
            new P10HostServRelay(JsonConfig.load(options.config()), options.debug()).run();
        } finally {
            if (errors != null) {
                log.removeHandler(errors);
                errors.close();
            }
        }
    }

    static Options parseArgs(String[] args) {
        Path config = Path.of("config.json");
        boolean configProvided = false;
        boolean debug = false;
        boolean daemon = false;
        boolean child = false;
        String action = null;
        for (String arg : args) {
            switch (arg) {
                case "--debug" -> debug = true;
                case "--deamon", "--daemon" -> daemon = true;
                case "--daemon-child" -> child = true;
                case "start", "stop", "--start", "--stop" -> {
                    if (action != null) {
                        throw new IllegalArgumentException("Only one daemon action is allowed");
                    }
                    if (arg.startsWith("--")) {
                        daemon = true;
                        action = arg.substring(2);
                    } else {
                        action = arg;
                    }
                }
                default -> {
                    if (arg.startsWith("--") || configProvided) {
                        throw new IllegalArgumentException("Unknown or duplicate argument: " + arg);
                    }
                    config = Path.of(arg);
                    configProvided = true;
                }
            }
        }
        if (daemon && child) {
            throw new IllegalArgumentException("Daemon child cannot start another daemon");
        }
        if (action != null && !daemon) {
            throw new IllegalArgumentException("start/stop requires --daemon or --deamon");
        }
        return new Options(config, debug, daemon, child, "stop".equals(action));
    }

    static List<String> daemonCommand(Options options) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName(), "--daemon-child"));
        if (options.debug()) {
            command.add("--debug");
        }
        command.add(options.config().toAbsolutePath().normalize().toString());
        return command;
    }

    static Path daemonPidPath(Options options) {
        Path config = options.config().toAbsolutePath().normalize();
        return config.resolveSibling(config.getFileName() + ".daemon.pid");
    }

    private static void manageDaemon(Options options) throws Exception {
        Path pidFile = daemonPidPath(options);
        Path lockFile = pidFile.resolveSibling(pidFile.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Another daemon start/stop is in progress");
            }
            ProcessHandle running = daemonProcess(options);
            if (options.stop()) {
                if (running == null) {
                    Files.deleteIfExists(pidFile);
                    System.out.println("Daemon is not running");
                    return;
                }
                if (!running.destroy()) {
                    throw new IOException("Could not stop daemon (PID " + running.pid() + ")");
                }
                try {
                    running.onExit().get(10, TimeUnit.SECONDS);
                } catch (TimeoutException ex) {
                    if (!running.destroyForcibly() && running.isAlive()) {
                        throw new IOException("Could not terminate daemon (PID " + running.pid() + ")", ex);
                    }
                    running.onExit().get(5, TimeUnit.SECONDS);
                }
                Files.deleteIfExists(pidFile);
                System.out.println("Daemon stopped (PID " + running.pid() + ")");
            } else if (running != null) {
                System.out.println("Daemon is already running (PID " + running.pid() + ")");
            } else {
                JsonConfig.load(options.config());
                Files.deleteIfExists(pidFile);
                startDaemon(options);
            }
        }
    }

    static ProcessHandle daemonProcess(Options options) throws IOException {
        Path pidFile = daemonPidPath(options);
        if (!Files.exists(pidFile)) {
            return null;
        }
        Properties state = new Properties();
        try (var input = Files.newInputStream(pidFile)) {
            state.load(input);
        }
        long pid;
        Instant started;
        try {
            pid = Long.parseLong(state.getProperty("pid", ""));
            started = Instant.parse(state.getProperty("started", ""));
            if (pid <= 0) {
                throw new IllegalArgumentException("Invalid PID");
            }
        } catch (IllegalArgumentException | DateTimeException ex) {
            throw new IOException("Invalid daemon PID file: " + pidFile, ex);
        }
        ProcessHandle process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
        if (process == null) {
            return null;
        }
        ProcessHandle.Info info = process.info();
        Instant actualStart = info.startInstant().orElseThrow(
                () -> new IOException("Cannot verify daemon start time (PID " + pid + ")"));
        if (!started.equals(actualStart)) {
            return null;
        }
        String config = options.config().toAbsolutePath().normalize().toString();
        String executable = info.command().orElseThrow(
                () -> new IOException("Cannot verify daemon executable (PID " + pid + ")"));
        if (!config.equals(state.getProperty("config"))
                || !Files.isSameFile(Path.of(executable), Path.of(daemonCommand(options).get(0)))) {
            throw new IOException("PID file does not identify this daemon: " + pidFile);
        }
        // Windows does not expose process arguments through ProcessHandle.
        if (info.arguments().isPresent()) {
            List<String> arguments = Arrays.asList(info.arguments().get());
            if (!arguments.contains(Main.class.getName()) || !arguments.contains("--daemon-child")
                    || !arguments.contains(config)) {
                throw new IOException("PID file does not identify this daemon: " + pidFile);
            }
        }
        return process;
    }

    private static void startDaemon(Options options) throws IOException {
        ProcessBuilder child = new ProcessBuilder(daemonCommand(options));
        child.redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("daemon.log").toFile()));
        child.redirectErrorStream(true);
        Process process = child.start();
        try {
            process.getOutputStream().close();
            Instant started = process.info().startInstant().orElseThrow(
                    () -> new IOException("Could not determine daemon start time"));
            Properties state = new Properties();
            state.setProperty("pid", Long.toString(process.pid()));
            state.setProperty("started", started.toString());
            state.setProperty("config", options.config().toAbsolutePath().normalize().toString());
            try (var output = Files.newOutputStream(daemonPidPath(options))) {
                state.store(output, "HostServ daemon");
            }
        } catch (IOException | RuntimeException ex) {
            process.destroyForcibly();
            try {
                Files.deleteIfExists(daemonPidPath(options));
            } catch (IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
        System.out.println("Daemon started (PID " + process.pid() + ")");
    }
}
