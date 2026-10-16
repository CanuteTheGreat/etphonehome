//! `etphonehome.exe` for Windows. All commands live in
//! `etphonehome_common::cli` (shared with the Linux build); this binary only
//! supplies the Administrator check.

use etphonehome_common::cli::{self, Platform};

#[cfg(windows)]
fn is_elevated() -> bool {
    use std::mem;
    use winapi::um::handleapi::CloseHandle;
    use winapi::um::processthreadsapi::{GetCurrentProcess, OpenProcessToken};
    use winapi::um::securitybaseapi::GetTokenInformation;
    use winapi::um::winnt::{TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};

    unsafe {
        let mut token = mem::zeroed();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return false;
        }

        let mut elevation: TOKEN_ELEVATION = mem::zeroed();
        let mut size = mem::size_of::<TOKEN_ELEVATION>() as u32;

        let info_ok = GetTokenInformation(
            token,
            TokenElevation,
            &mut elevation as *mut _ as *mut _,
            size,
            &mut size,
        ) != 0;

        // OpenProcessToken succeeded above, so `token` is a real kernel handle
        // that must be closed regardless of which path we take out of here —
        // it was previously being leaked on every call to this function.
        CloseHandle(token);

        if !info_ok {
            return false;
        }

        elevation.TokenIsElevated != 0
    }
}

#[cfg(not(windows))]
fn is_elevated() -> bool {
    false
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    cli::main(Platform {
        name: "Windows",
        privileged: is_elevated,
        elevate_hint: "Please run from an Administrator prompt.",
    })
    .await
}
