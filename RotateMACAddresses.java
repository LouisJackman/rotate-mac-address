/**
 * Rotate MAC addresses on an interval. Run `java RotateMacAddresses.java --help` for usage.
 */

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.time.Duration;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.util.stream.Collectors.joining;

/**
 * A NIC, network interface card, vendor: a pair consisting of a vendor identifier and a MAC address prefix.
 */
enum NICVendor {
    Intel,
    Foxconn,
    HewlettPackard,
    Cisco,
    Amd;

    @Override
    public String toString() {
        return switch (this) {
            case Intel -> "Intel";
            case Foxconn -> "Foxconn";
            case HewlettPackard -> "HP";
            case Cisco -> "Cisco";
            case Amd -> "AMD";
        };
    }

    /**
     * @return A vendor-specific MAC address prefix.
     */
    public String macAddressPrefix() {
        return switch (this) {
            case Intel -> "00:1b:77";
            case Foxconn -> "00:01:6c";
            case HewlettPackard -> "00:1b:78";
            case Cisco -> "00:10:2";
            case Amd -> "00:0c:87";
        };
    }

    private static final NICVendor[] values = values();
    private static final Random valuesRandomGenerator = new Random();

    /**
     * @return A randomly chosen NIC vendor.
     */
    public static NICVendor randomlyChoose() {
        var i = valuesRandomGenerator.nextInt(values.length);
        return values[i];
    }
}

/**
 * Generate commands for settings MAC addresses, for a given platform.
 */
sealed interface MacAddressSettingCommandFactory {
    List<String> createMacAddressSettingCommand(
            String deviceName,
            String newMacAddress
    );
}

/**
 * Generate commands for setting MAC addresses on a non-Linux Unix system.
 */
final class MacAddressSettingUnixCommandFactory implements MacAddressSettingCommandFactory {
    public List<String> createMacAddressSettingCommand(
            String deviceName,
            String newMacAddress) {
        return List.of("ifconfig", deviceName, "ether", newMacAddress);
    }
}

/**
 * Generate commands for setting MAC addresses on a Linux system.
 */
final class MacAddressSettingLinuxCommandFactory implements MacAddressSettingCommandFactory {
    public List<String> createMacAddressSettingCommand(
            String deviceName,
            String newMacAddress) {
        return List.of("ip", "link", "set", "dev", deviceName, "addr", newMacAddress);
    }
}

/**
 * A fully generated MAC address, plus the vendor its prefix represents.
 */
record MacAddress(NICVendor vendor, String address) {
    private static final Random randomGenerator = new Random();

    /**
     * @return A randomly generated MAC address with a prefix from a known vendor.
     */
    public static MacAddress createRandom() {
        var vendorChoice = NICVendor.randomlyChoose();
        var suffix = IntStream

                // Skip the first element, as that is the prefix determined by
                // the NIC vendor.
                .range(1, 4)

                .boxed()
                .map(_ ->
                        String.valueOf(randomGenerator.nextInt(9))
                        + randomGenerator.nextInt(9)
                )
                .collect(joining(":"));
        var address = vendorChoice.macAddressPrefix() + ":" + suffix;
        return new MacAddress(vendorChoice, address);
    }
}

/**
 * Actually invoke side effects against the current OS, or just _log_ what would happen?
 */
enum RunMode { dryRun, actualRun }

/**
 * A failure happened while rotating a MAC address, something anticipated such as the MAC-setting command returning a
 * non-zero status code.
 */
final class MacAddressRotationException extends RuntimeException {
    public MacAddressRotationException(String message) { super(message); }
}

/**
 * Rotate a MAC address for a provided device name. Tolerate up to `MACAddressRotater.MAX_EXCEPTION_COUNT` failures
 * before failing overall.
 */
final class MACAddressRotater {
    private static final long MAX_EXCEPTION_COUNT = 3;
    private static final double CYCLE_VARIANCE = .25;

    private final String deviceName;
    private final MacAddressSettingCommandFactory macAddressSettingCommandFactory;
    private final Random variator = new Random();

    public MACAddressRotater(
            String deviceName,
            MacAddressSettingCommandFactory macAddressSettingCommandFactory) {
        this.deviceName = deviceName;
        this.macAddressSettingCommandFactory = macAddressSettingCommandFactory;
    }

    private double variate(long seconds) {
        var delta = (variator.nextDouble() - .5) * CYCLE_VARIANCE;
        return ((double)seconds) + (((double)seconds) * delta);
    }

    private void setMacAddress(RunMode runMode) throws InterruptedException {
        var macAddress = MacAddress.createRandom();
        var setCommand = macAddressSettingCommandFactory.createMacAddressSettingCommand(
                deviceName,
                macAddress.address()
        );
        switch (runMode) {
            case RunMode.dryRun -> {
                var command = String.join(" ", setCommand);
                out.printf("Would run `%s`\n", command);
            }
            case RunMode.actualRun -> {
                final Process process;
                try {
                    process = new ProcessBuilder(setCommand).inheritIO().start();
                } catch (IOException exception) {
                    throw new MacAddressRotationException(exception.getMessage());
                }
                if (process.waitFor() != 0) {
                    throw new MacAddressRotationException(
                            "the subprocess setting the MAC address failed");
                }
            }
        }
        out.printf(
                "Set to MAC address %s of vendor %s\n",
                macAddress.address(),
                macAddress.vendor());
    }

    /**
     * Continually rotate a MAC address for the device overtime. Interrupt the thread or trigger a JVM shutdown to stop
     * the rotation.
     */
    public void rotate(long cycleSeconds, RunMode runMode) throws InterruptedException {
        var exceptionsSoFar = new ArrayDeque<Exception>();
        for (;;) {
            try {
                setMacAddress(runMode);
            } catch (MacAddressRotationException exception) {
                var remaining = MAX_EXCEPTION_COUNT - exceptionsSoFar.size();
                err.printf("An error occurred: %s\n", exception);
                if (MAX_EXCEPTION_COUNT < exceptionsSoFar.size()) {
                    var message = exceptionsSoFar
                            .stream()
                            .map(Object::toString)
                            .collect(joining("\n"));
                    throw new MacAddressRotationException(message);
                } else {
                    err.printf(
                            "The program will stop if %d more errors occur " +
                            "sequentially\n",
                            remaining);
                    exceptionsSoFar.add(exception);
                }
            }
            var variation = variate(cycleSeconds);
            var duration = Duration.ofSeconds(Math.round(variation));
            out.printf(
                    "Waiting for %d seconds until the next rotation\n",
                    duration.getSeconds()
            );
            Thread.sleep(duration);
        }
    }
}

/**
 * A provided command-line argument was invalid in some way.
 */
final class InvalidArgException extends RuntimeException {
    public InvalidArgException(String message) { super(message); }
}

/**
 * Arguments provided by the environment, e.g. command-line arguments or environment variables. See
 * `RotateMacAddresses.USAGE` for context about each one.
 */
record Args(boolean viewHelp, String deviceName, long cycleSeconds, boolean dryRun) {
    public static final boolean DEFAULT_VIEW_HELP = false;
    public static final String DEFAULT_DEVICE_NAME = "eth0";
    public static final long DEFAULT_CYCLE_SECS = 30 * 60;
    public static final boolean DEFAULT_DRY_RUN = false;

    private static final Pattern flagWithValue = Pattern.compile("^--([^\\s=]+)=([^\\s=]+)$");
    private static final Pattern booleanFlag = Pattern.compile("^--([^\\s=]+)$");

    private record NameValue(String name, String value) { }

    private static boolean isAffirmative(String s) {
        return switch (s.toLowerCase()) {
            case "t":
            case "y":
            case "yes":
            case "1":
            case "true":
                yield true;
            default:
                yield false;
        };
    }

    private static <T> void setOrThrowIfDuplicated(AtomicReference<T> ref, T value, String argName) {
        if (ref.compareAndExchange(null, value) != null) {
            throw new InvalidArgException("duplicated " + argName + " argument");
        }
    }

    /**
     * Parse from command-line arguments.
     */
    public static Args parse(String... args) {
        var viewHelp = new AtomicReference<Boolean>(null);
        var deviceName = new AtomicReference<String>(null);
        var cycleSeconds = new AtomicReference<Long>(null);
        var dryRun = new AtomicReference<Boolean>(null);
        Arrays
                .stream(args)
                .parallel()
                .map(arg -> {
                    var flagWithValueMatch = flagWithValue.matcher(arg);
                    if (flagWithValueMatch.matches()) {
                        return Optional.of(new NameValue(flagWithValueMatch.group(1), flagWithValueMatch.group(2)));
                    }
                    var booleanFlagMatch = booleanFlag.matcher(arg);
                    return booleanFlagMatch.matches()
                            ? Optional.of(new NameValue(booleanFlagMatch.group(1), "true"))
                            : Optional.<NameValue>empty();
                })
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .forEach(nameValue -> {
                    switch (nameValue.name().toLowerCase()) {
                        case "help" -> setOrThrowIfDuplicated(viewHelp, isAffirmative(nameValue.value()), "help");
                        case "device-name" -> setOrThrowIfDuplicated(deviceName, nameValue.value(), "device-name");
                        case "cycle-secs" -> setOrThrowIfDuplicated(
                                cycleSeconds,
                                Long.valueOf(nameValue.value()),
                                "cycle-secs");
                        case "dry-run" -> setOrThrowIfDuplicated(dryRun, isAffirmative(nameValue.value()), "dry-run");
                        default -> throw new InvalidArgException("unknown argument: " + nameValue.name());
                    }
                });
        return new Args(
                (viewHelp.get() == null) ? DEFAULT_VIEW_HELP : viewHelp.get(),
                (deviceName.get() == null) ? DEFAULT_DEVICE_NAME : deviceName.get(),
                (cycleSeconds.get() == null) ? DEFAULT_CYCLE_SECS : cycleSeconds.get(),
                (dryRun.get() == null) ? DEFAULT_DRY_RUN : dryRun.get());
    }
}

/**
 * Program entrypoint and top-level error-handling.
 */
public class RotateMACAddresses {
    public static final String USAGE = """
            
            java RotateMACAddresses.java
            
            \t       --help    View this help section
            \t--device-name    The network device name whose MAC addresses to rotate, e.g. `eth0`
            \t                 Default: %s
            \t --cycle-secs    The average seconds between each cycle, with variation added
            \t                 Default: %d
            \t    --dry-run    Whether to dry-run the MAC address-setting commands
            \t                 Default: %b
            
            """.formatted(Args.DEFAULT_DEVICE_NAME, Args.DEFAULT_CYCLE_SECS, Args.DEFAULT_DRY_RUN);

    private static void fail(String message) {
        err.println(message);
        exit(1);
    }

    private static boolean isLinux() {
        var os = System.getProperty("os.name");
        return os.equalsIgnoreCase("linux");
    }

    private static void onInterruption() {
        out.println("Interrupt detected; stopping MAC rotation and exiting...");
    }

    private static void startRotation(
            MACAddressRotater rotator,
            long cycleSeconds,
            RunMode runMode) {
        try {
            rotator.rotate(cycleSeconds, runMode);
        } catch (MacAddressRotationException exception) {
            fail("An error occurred while rotating the MAC address: " + exception);
        } catch (InterruptedException exception) {
            onInterruption();
        }
    }

    public static void main(String[] args) {
        var macAddressSettingCommandFactory = isLinux()
                ? new MacAddressSettingLinuxCommandFactory()
                : new MacAddressSettingUnixCommandFactory();

        final Args parsedArgs;
        try {
            parsedArgs = Args.parse(args);
        } catch (InvalidArgException exception) {
            err.printf("Invalid arguments: %s\n", exception);
            fail(USAGE);
            return;
        }
        if (parsedArgs.viewHelp()) {
            out.println(USAGE);
            return;
        }
        getRuntime().addShutdownHook(new Thread(RotateMACAddresses::onInterruption));
        var runMode = parsedArgs.dryRun() ? RunMode.dryRun : RunMode.actualRun;
        var macAddressRotater = new MACAddressRotater(
                parsedArgs.deviceName(),
                macAddressSettingCommandFactory
        );
        startRotation(macAddressRotater, parsedArgs.cycleSeconds(), runMode);
    }
}
