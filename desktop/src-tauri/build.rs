use std::env;
use std::fs::File;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("manifest directory"));
    let source_icon =
        manifest_dir.join("../../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png");
    println!("cargo:rerun-if-changed={}", source_icon.display());
    let generated_icon =
        PathBuf::from(env::var("OUT_DIR").expect("build output directory")).join("armusic.ico");

    let image = ico::IconImage::read_png(
        File::open(&source_icon).expect("ARMusic launcher icon should be available"),
    )
    .expect("ARMusic launcher icon should be a valid PNG");
    let mut icon_dir = ico::IconDir::new(ico::ResourceType::Icon);
    icon_dir.add_entry(ico::IconDirEntry::encode(&image).expect("launcher icon should encode"));
    icon_dir
        .write(File::create(&generated_icon).expect("generated icon should be writable"))
        .expect("Windows icon should be generated");

    let windows = tauri_build::WindowsAttributes::new().window_icon_path(generated_icon);
    let attributes = tauri_build::Attributes::new().windows_attributes(windows);
    tauri_build::try_build(attributes).expect("Tauri build setup should succeed");
}
