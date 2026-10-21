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

/// Started by the Service Control Manager (`etphonehome.exe service
/// --windows-service`, registered by the MSI): run the ETPH service, which
/// the tray app talks to, until Windows stops it.
#[cfg(windows)]
mod winsvc {
    use std::ffi::OsString;
    use std::time::Duration;
    use windows_service::service::{
        ServiceControl, ServiceControlAccept, ServiceExitCode, ServiceState, ServiceStatus,
        ServiceType,
    };
    use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
    use windows_service::{define_windows_service, service_dispatcher};

    pub const NAME: &str = "ETPH";

    define_windows_service!(ffi_service_main, service_main);

    pub fn start() -> anyhow::Result<()> {
        service_dispatcher::start(NAME, ffi_service_main)?;
        Ok(())
    }

    fn status(state: ServiceState, accept: ServiceControlAccept) -> ServiceStatus {
        ServiceStatus {
            service_type: ServiceType::OWN_PROCESS,
            current_state: state,
            controls_accepted: accept,
            exit_code: ServiceExitCode::Win32(0),
            checkpoint: 0,
            wait_hint: Duration::from_secs(15),
            process_id: None,
        }
    }

    fn service_main(_args: Vec<OsString>) {
        let (tx, rx) = tokio::sync::watch::channel(false);
        let handler = move |ev| match ev {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                let _ = tx.send(true);
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        };
        let Ok(handle) = service_control_handler::register(NAME, handler) else {
            return;
        };
        let _ = handle.set_service_status(status(
            ServiceState::Running,
            ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        ));
        let result = tokio::runtime::Runtime::new().map(|rt| {
            rt.block_on(etphonehome_common::daemon::run(
                etphonehome_common::daemon::Options::default(),
                rx,
            ))
        });
        if let Ok(Err(e)) | Err(e) = result.map_err(anyhow::Error::from) {
            log::error!("ETPH service failed: {:#}", e);
        }
        let _ =
            handle.set_service_status(status(ServiceState::Stopped, ServiceControlAccept::empty()));
    }
}

/// The service has no console: log to %ProgramData%\ETPH\service.log.
#[cfg(windows)]
fn service_logger() {
    let dir = etphonehome_common::daemon::state_dir();
    let _ = std::fs::create_dir_all(&dir);
    let mut b = env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"));
    if let Ok(f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("service.log"))
    {
        b.target(env_logger::Target::Pipe(Box::new(f)));
    }
    b.init();
}

fn main() -> anyhow::Result<()> {
    #[cfg(windows)]
    if std::env::args().any(|a| a == "--windows-service") {
        service_logger();
        return winsvc::start();
    }
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    tokio::runtime::Runtime::new()?.block_on(cli::main(Platform {
        name: "Windows",
        privileged: is_elevated,
        elevate_hint: "Please run from an Administrator prompt.",
    }))
}
