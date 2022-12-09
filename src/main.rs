#![forbid(unsafe_code)]

use {
    anyhow::{anyhow, Error},
    clap::Parser,
    rand::prelude::{thread_rng, Rng, SliceRandom, ThreadRng},
    std::{
        fmt::{self, Display, Formatter},
        process::Command,
        thread::sleep,
        time::Duration,
    },
};

const NAME: &str = "rotate-mac-address";
const AUTHOR: &str = "Louis Jackman";
const ABOUT: &str = "Rotate MAC addresses on a specified interval, with a bit of variation \
    added. Requires superuser privileges. Supports macOS and Linux.";

const DEFAULT_DEVICE_NAME: &str = "eth0";
const DEFAULT_CYCLE_SECONDS: usize = 30 * 60;

const CYCLE_VARIANCE: f64 = 0.25;
const MAX_AMOUNT_OF_ERRORS: usize = 3;

#[derive(Copy, Clone, Debug)]
enum Vendor {
    Intel,
    HewlettPackard,
    Foxconn,
    Cisco,
    Amd,
}

impl Vendor {
    fn mac_address_prefix(self) -> &'static str {
        match self {
            Self::Intel => "00:1b:77",
            Self::HewlettPackard => "00:1b:78",
            Self::Foxconn => "00:01:6c",
            Self::Cisco => "00:10:29",
            Self::Amd => "00:0c:87",
        }
    }
}

impl Display for Vendor {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        let to_display = match self {
            Self::Intel => "Intel",
            Self::HewlettPackard => "HP",
            Self::Foxconn => "Foxconn",
            Self::Cisco => "Cisco",
            Self::Amd => "AMD",
        };
        write!(f, "{}", to_display)
    }
}

static VENDORS: [Vendor; 5] = {
    use Vendor::*;
    [Intel, HewlettPackard, Foxconn, Cisco, Amd]
};

struct VendorPicker<'r>(&'r mut ThreadRng);

impl<'r> VendorPicker<'r> {
    fn new(rng: &'r mut ThreadRng) -> Self {
        Self(rng)
    }

    fn pick(&mut self) -> Vendor {
        let Self(rng) = self;
        *VENDORS.choose(rng).expect("`VENDORS` cannot be empty")
    }
}

struct SetMacAddressSpec<'s> {
    interface_name: &'s str,
    new_mac_address: &'s str,
}

#[cfg(all(target_family = "unix", not(target_os = "linux")))]
const PROGRAM: &str = "ifconfig";

#[cfg(all(target_family = "unix", not(target_os = "linux")))]
fn new_set_mac_address<'a>(spec: SetMacAddressSpec<'a>) -> (Command, [&'a str; 3]) {
    let args: [&'a str; 3] = [spec.interface_name, "ether", spec.new_mac_address];

    let mut cmd = Command::new(PROGRAM);
    cmd.args(args);
    (cmd, args)
}

#[cfg(all(target_family = "unix", target_os = "linux"))]
const PROGRAM: &str = "ip";

#[cfg(all(target_family = "unix", target_os = "linux"))]
fn new_set_mac_address<'a>(spec: SetMacAddressSpec<'a>) -> (Command, [&'a str; 6]) {
    let args: [&'a str; 6] = [
        "link",
        "set",
        "dev",
        spec.interface_name,
        "addr",
        spec.new_mac_address,
    ];

    let mut cmd = Command::new(PROGRAM);
    cmd.args(args);
    (cmd, args)
}

fn random_digit(rng: &mut ThreadRng) -> u8 {
    rng.gen_range(1..=9)
}

fn new_random_mac_address<'r>(
    rng: &mut ThreadRng,
    picker: &mut VendorPicker<'r>,
) -> (Vendor, String) {
    let vendor = picker.pick();

    let new_address = {
        const LENGTH: u8 = 3;
        (1..=LENGTH).fold(
            {
                let mut addr = vendor.mac_address_prefix().to_owned();
                addr.push(':');
                addr
            },
            |mut addr, i| {
                let (first, second) = (random_digit(rng), random_digit(rng));
                addr.push_str(&first.to_string());
                addr.push_str(&second.to_string());
                if i < LENGTH {
                    addr.push(':');
                }
                addr
            },
        )
    };

    (vendor, new_address)
}

fn variate(rng: &mut ThreadRng, seconds: usize, variance: f64) -> f64 {
    let base: f64 = rng.gen_range(0.0..=1.0);
    let delta = (base - 0.5) * variance;
    (seconds as f64) + ((seconds as f64) * delta)
}

fn set_mac_address(
    rng: &mut ThreadRng,
    picker: &mut VendorPicker,
    interface_name: &str,
    dry_run: bool,
) -> Result<(String, Vendor), Error> {
    let (vendor, new_address) = new_random_mac_address(rng, picker);
    let (mut cmd, args) = new_set_mac_address(SetMacAddressSpec {
        interface_name,
        new_mac_address: &new_address,
    });

    if dry_run {
        let length = args.len();
        let args_str = args.iter().zip(1..).fold(String::new(), |mut s, (arg, i)| {
            s.push_str(arg);
            if i < length {
                s.push(' ');
            }
            s
        });
        println!(
            "Dry-running enabled; would otherwise run \
            `{PROGRAM} {args_str}`"
        );
    } else {
        cmd.output()?;
    };
    Ok((new_address, vendor))
}

fn collate_errors(errors: Vec<Error>) -> Error {
    let length = errors.len();
    let message = errors.iter().map(|e| e.to_string()).zip(1..).fold(
        String::new(),
        |mut overall, (desc, i)| {
            overall.push_str(&desc);
            if i < length {
                overall.push_str(", ");
            }
            overall
        },
    );
    anyhow!("Failures: {message}")
}

fn rotate_mac_addresses(
    rng: &mut ThreadRng,
    picker: &mut VendorPicker,
    interface_name: &str,
    cycle_seconds: usize,
    dry_run: bool,
) -> Result<(), Error> {
    let mut errors: Vec<Error> = vec![];
    errors.reserve(MAX_AMOUNT_OF_ERRORS);

    loop {
        let change_result = set_mac_address(rng, picker, interface_name, dry_run);

        match change_result {
            Ok((new_address, vendor)) => {
                errors.clear();
                println!(
                    "Successfully changed MAC address on interface \
                    {interface_name} to {new_address} of vendor {vendor}"
                );
            }
            Err(err) => {
                errors.push(err);
                let errors_count = MAX_AMOUNT_OF_ERRORS - errors.len();
                eprintln!(
                    "Failed to change MAC address on interface \
                        {interface_name}. Only {errors_count} sequential \
                        errors left until the program aborts."
                );
                if MAX_AMOUNT_OF_ERRORS <= errors.len() {
                    break Err(collate_errors(errors));
                }
            }
        }

        let variation = variate(rng, cycle_seconds, CYCLE_VARIANCE);
        let duration = Duration::from_millis((variation.round() * 1000.0) as u64);
        println!(
            "waiting for {} seconds until the next rotation",
            duration.as_secs(),
        );
        sleep(duration);
    }
}

#[derive(Parser, Debug)]
#[command(name = NAME)]
#[command(author = AUTHOR)]
#[command(about = ABOUT)]
struct Flags {
    #[arg(long, default_value_t = DEFAULT_DEVICE_NAME.to_owned())]
    interface_name: String,
    #[arg(long, default_value_t = DEFAULT_CYCLE_SECONDS)]
    cycle_seconds: usize,
    #[arg(long, default_value_t = false)]
    dry_run: bool,
}

fn main() -> Result<(), Error> {
    let flags = Flags::parse();
    let mut rng = thread_rng();
    let mut vendor_picker_rng = thread_rng();
    let mut vendor_picker = VendorPicker::new(&mut vendor_picker_rng);

    rotate_mac_addresses(
        &mut rng,
        &mut vendor_picker,
        &flags.interface_name,
        flags.cycle_seconds,
        flags.dry_run,
    )
}
