//! `tun-pipe <ifname>`: see the library docs. Started by the phone app under su.

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 || args[1] != "tun-pipe" {
        eprintln!("usage: tun-pipe tun-pipe <ifname>");
        std::process::exit(2);
    }
    if let Err(e) = run(&args[2]) {
        eprintln!("tun-pipe: {e}");
        std::process::exit(1);
    }
}

#[cfg(unix)]
fn run(ifname: &str) -> std::io::Result<()> {
    let tun = tun_pipe::tun::open(ifname)?;
    let tun_w = tun.try_clone()?;
    tun_pipe::pump(tun, tun_w, std::io::stdin(), std::io::stdout())
}

#[cfg(not(unix))]
fn run(_ifname: &str) -> std::io::Result<()> {
    Err(std::io::Error::other("tun-pipe needs a unix system"))
}
