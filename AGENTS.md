# Rotate MAC Addresses — AI Coding Agent Instructions

## Project Overview

Single-file Java 23 CLI tool that continuously rotates MAC addresses using well-known NIC vendor prefixes. Runs directly via source-file execution (JEP 458) with no build system or external dependencies.

## Running

```sh
# Run (requires Java 23+)
java RotateMACAddresses.java --help
java RotateMACAddresses.java --device-name=en0 --dry-run

# Actual rotation requires superuser privileges
sudo java RotateMACAddresses.java --device-name=en0
```

There is no build step, no test suite, and no dependency management. The entire program is `RotateMACAddresses.java`.

## Architecture

All types live in `RotateMACAddresses.java`:

- **`NICVendor`** enum: known vendor MAC prefixes (Intel, Foxconn, HP, Cisco, AMD) with random selection
- **`MacAddressSettingCommandFactory`** sealed interface: platform-specific command generation (`ifconfig` for Unix/macOS, `ip link` for Linux)
- **`MacAddress`** record: generates random MAC addresses with real vendor prefixes
- **`MACAddressRotater`**: main loop — rotates addresses on an interval with +/-25% time variance, tolerates up to 3 consecutive failures
- **`Args`** record: CLI argument parsing (`--help`, `--device-name`, `--cycle-secs`, `--dry-run`)

## Conventions

- Uses Java 23 Markdown-style JavaDoc (`///` syntax)
- No third-party dependencies; standard library only
- Licence: AGPL v3
