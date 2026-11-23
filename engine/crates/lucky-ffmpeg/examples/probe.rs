use std::fs::File;
use std::os::fd::OwnedFd;

fn main() {
    lucky_ffmpeg::install_log_bridge(false);
    println!("{:?}", lucky_ffmpeg::versions());
    for path in std::env::args().skip(1) {
        let file = match File::open(&path) {
            Ok(file) => file,
            Err(error) => {
                eprintln!("{path}: {error}");
                continue;
            }
        };
        match lucky_ffmpeg::Input::open_fd(OwnedFd::from(file)) {
            Ok(input) => println!("{path}: {:#?}", input.media_info()),
            Err(error) => println!("{path}: error: {error}"),
        }
    }
}
