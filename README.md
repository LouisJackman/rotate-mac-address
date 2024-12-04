# Rotate MAC Addresses

[![pipeline status](https://gitlab.com/louis.jackman/rotate_mac_address/badges/master/pipeline.svg)](https://gitlab.com/louis.jackman/rotate_mac_address/-/commits/master)

Randomise MAC addresses with well-known NIC vendor prefixes on a specified
interval, with a bit of variation added. Likely requires superuser
privileges. Supports Unix-like OSes like macOS, Linux, and the BSDs. Requires
a Java 23 runtime.

Run with `java RotateMACAddresses.java`. Pass the `--help` flag to see the
available commands:

```
$ java RotateMACAddresses.java --help

java RotateMACAddresses.java

               --help    View this help section
        --device-name    The network device name whose MAC addresses to rotate, e.g. `eth0`
                         Default: eth0
         --cycle-secs    The average seconds between each cycle, with variation added
                         Default: 1800
            --dry-run    Whether to dry-run the MAC address-setting commands
                         Default: false
```

This repository is hosted [on
GitLab.com](https://gitlab.com/louis.jackman/rotate-mac-address). If you're
seeing this on GitHub, you're on the official GitHub mirror. [Go to
GitLab](https://gitlab.com/louis.jackman/rotate-mac-address) to contribute.

