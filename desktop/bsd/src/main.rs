//! `etphonehome` for BSD. All commands live in `etphonehome_common::cli`
//! (shared with Linux and Windows); this binary only supplies the root check.

use etphonehome_common::cli::{self, Platform};

fn is_root() -> bool {
    // SAFETY: geteuid has no preconditions and cannot fail.
    unsafe { libc::geteuid() == 0 }
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    cli::main(Platform {
        name: "BSD",
        privileged: is_root,
        elevate_hint: "Please run as root (sudo/doas etphonehome ...).",
    })
    .await
}
