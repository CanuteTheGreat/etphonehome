// Windows: embed an application manifest asking for Administrator (the GUI
// creates the Wintun adapter and routes). Cross-built with mingw, so the
// resource is compiled with windres and linked as an object.
use std::{env, path::PathBuf, process::Command};

fn main() {
    println!("cargo:rerun-if-changed=windows/etph-gui.rc");
    println!("cargo:rerun-if-changed=windows/etph-gui.manifest");
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows")
        || env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("gnu")
    {
        return;
    }
    let dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("windows");
    let out = PathBuf::from(env::var("OUT_DIR").unwrap()).join("etph-gui-res.o");
    let windres = if cfg!(windows) { "windres" } else { "x86_64-w64-mingw32-windres" };
    let st = Command::new(windres)
        .current_dir(&dir)
        .args(["-i", "etph-gui.rc", "-O", "coff", "-o"])
        .arg(&out)
        .status()
        .unwrap_or_else(|e| panic!("cannot run {windres} (install mingw-w64): {e}"));
    assert!(st.success(), "{windres} failed");
    println!("cargo:rustc-link-arg-bins={}", out.display());
}
