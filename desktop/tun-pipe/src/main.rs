//! `tun-pipe tun-pipe <ifname>`: see the library docs. Started by the phone app under su.
//! `tun-pipe su-serve <socket>` / `tun-pipe su-client <socket> -c <cmd>`: the test-only su
//! bridge (see `su_bridge`) the emulator end-to-end test installs.

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let r = match args.get(1).map(String::as_str) {
        Some("tun-pipe") if args.len() == 3 => run(&args[2]),
        Some("su-serve") if args.len() == 3 => serve(&args[2]),
        Some("su-client") => client(&args[2..]),
        _ => {
            eprintln!("usage: tun-pipe tun-pipe <ifname> | su-serve <socket> | su-client <socket> -c <cmd>");
            std::process::exit(2);
        }
    };
    match r {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            eprintln!("tun-pipe: {e}");
            std::process::exit(1);
        }
    }
}

#[cfg(unix)]
fn run(ifname: &str) -> std::io::Result<i32> {
    let tun = tun_pipe::tun::open(ifname)?;
    let tun_w = tun.try_clone()?;
    tun_pipe::pump(tun, tun_w, std::io::stdin(), std::io::stdout())?;
    Ok(0)
}

#[cfg(unix)]
fn serve(sock: &str) -> std::io::Result<i32> {
    tun_pipe::su_bridge::serve(sock).map(|()| 0)
}

#[cfg(unix)]
fn client(args: &[String]) -> std::io::Result<i32> {
    let (Some(sock), Some(i)) = (args.first(), args.iter().position(|a| a == "-c")) else {
        return Err(std::io::Error::other("usage: su-client <socket> -c <cmd>"));
    };
    let Some(cmd) = args.get(i + 1) else {
        return Err(std::io::Error::other("su-client: -c needs a command"));
    };
    tun_pipe::su_bridge::client(
        sock,
        cmd,
        std::io::stdin(),
        &mut std::io::stdout(),
        &mut std::io::stderr(),
    )
}

#[cfg(not(unix))]
fn run(_ifname: &str) -> std::io::Result<i32> {
    Err(std::io::Error::other("tun-pipe needs a unix system"))
}
#[cfg(not(unix))]
fn serve(_s: &str) -> std::io::Result<i32> {
    Err(std::io::Error::other("tun-pipe needs a unix system"))
}
#[cfg(not(unix))]
fn client(_a: &[String]) -> std::io::Result<i32> {
    Err(std::io::Error::other("tun-pipe needs a unix system"))
}
