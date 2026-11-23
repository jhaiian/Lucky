use jni::objects::{JByteBuffer, JObject, JValue};
use jni::refs::Global;
use jni::{JavaVM, jni_sig, jni_str};
use lucky_player::{AudioOutput, EncodedCodec};

const CAPACITY_BYTES: i32 = 64 * 1024;
const ENCODED_CAPACITY_BYTES: i32 = 256 * 1024;

pub struct JniAudioOutput {
    vm: JavaVM,
    sink: Global<JObject<'static>>,
    buffer: *mut u8,
    capacity: usize,
}

unsafe impl Send for JniAudioOutput {}

impl JniAudioOutput {
    pub fn new(vm: JavaVM, sink: Global<JObject<'static>>) -> Self {
        Self { vm, sink, buffer: std::ptr::null_mut(), capacity: 0 }
    }

    fn call_void(&self, name: &'static str) {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let method = match name {
                "play" => jni_str!("play"),
                "pause" => jni_str!("pause"),
                _ => jni_str!("release"),
            };
            env.call_method(&self.sink, method, jni_sig!("()V"), &[])?;
            Ok(())
        });
        if let Err(error) = result {
            log::warn!("audio sink {name} failed: {error}");
        }
    }

    fn adopt(&mut self, result: jni::errors::Result<Option<(*mut u8, usize)>>) -> bool {
        match result {
            Ok(Some((address, capacity))) if !address.is_null() && capacity >= 4 => {
                self.buffer = address;
                self.capacity = capacity;
                true
            }
            Ok(_) => false,
            Err(error) => {
                log::warn!("audio sink open failed: {error}");
                false
            }
        }
    }

    fn transfer(&mut self, bytes: usize) -> Option<usize> {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<i32> {
            env.call_method(&self.sink, jni_str!("write"), jni_sig!("(I)I"), &[JValue::Int(bytes as i32)])?.i()
        });
        match result {
            Ok(written) if written >= 0 => Some(written as usize),
            Ok(code) => {
                log::warn!("audio write failed with {code}");
                None
            }
            Err(error) => {
                log::warn!("audio write failed: {error}");
                None
            }
        }
    }
}

impl AudioOutput for JniAudioOutput {
    fn open(&mut self, sample_rate: u32, channels: u32) -> bool {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<Option<(*mut u8, usize)>> {
            let value = env
                .call_method(
                    &self.sink,
                    jni_str!("open"),
                    jni_sig!("(III)Ljava/nio/ByteBuffer;"),
                    &[JValue::Int(sample_rate as i32), JValue::Int(channels as i32), JValue::Int(CAPACITY_BYTES)],
                )?
                .l()?;
            if value.is_null() {
                return Ok(None);
            }
            let buffer = env.cast_local::<JByteBuffer>(value)?;
            let address = env.get_direct_buffer_address(&buffer)?;
            let capacity = env.get_direct_buffer_capacity(&buffer)?;
            Ok(Some((address, capacity)))
        });
        let opened = self.adopt(result);
        if opened {
            log::info!("audio output open: {sample_rate} Hz, {channels} channels");
        }
        opened
    }

    fn open_encoded(&mut self, sample_rate: u32, channels: u32, codec: EncodedCodec) -> bool {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<Option<(*mut u8, usize)>> {
            let value = env
                .call_method(
                    &self.sink,
                    jni_str!("openEncoded"),
                    jni_sig!("(IIII)Ljava/nio/ByteBuffer;"),
                    &[
                        JValue::Int(sample_rate as i32),
                        JValue::Int(channels as i32),
                        JValue::Int(codec.encoding()),
                        JValue::Int(ENCODED_CAPACITY_BYTES),
                    ],
                )?
                .l()?;
            if value.is_null() {
                return Ok(None);
            }
            let buffer = env.cast_local::<JByteBuffer>(value)?;
            let address = env.get_direct_buffer_address(&buffer)?;
            let capacity = env.get_direct_buffer_capacity(&buffer)?;
            Ok(Some((address, capacity)))
        });
        let opened = self.adopt(result);
        if opened {
            log::info!("audio passthrough open: {} at {sample_rate} Hz, {channels} channels", codec.name());
        }
        opened
    }

    fn write(&mut self, samples: &[f32]) -> Option<usize> {
        if self.buffer.is_null() || samples.is_empty() {
            return Some(0);
        }
        let count = samples.len().min(self.capacity / 4);
        unsafe { std::ptr::copy_nonoverlapping(samples.as_ptr() as *const u8, self.buffer, count * 4) };
        self.transfer(count * 4).map(|bytes| bytes / 4)
    }

    fn write_encoded(&mut self, bytes: &[u8]) -> Option<usize> {
        if self.buffer.is_null() || bytes.is_empty() {
            return Some(0);
        }
        let count = bytes.len().min(self.capacity);
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), self.buffer, count) };
        self.transfer(count)
    }

    fn played_frames(&mut self) -> Option<i64> {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<i64> {
            env.call_method(&self.sink, jni_str!("playedFrames"), jni_sig!("()J"), &[])?.j()
        });
        match result {
            Ok(frames) if frames >= 0 => Some(frames),
            _ => None,
        }
    }

    fn play(&mut self) {
        self.call_void("play");
    }

    fn pause(&mut self) {
        self.call_void("pause");
    }

    fn flush(&mut self) -> bool {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<bool> {
            env.call_method(&self.sink, jni_str!("flush"), jni_sig!("()Z"), &[])?.z()
        });
        result.unwrap_or(false)
    }

    fn close(&mut self) {
        self.call_void("release");
        self.buffer = std::ptr::null_mut();
        self.capacity = 0;
    }

    fn max_channels(&mut self) -> u32 {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<i32> {
            env.call_method(&self.sink, jni_str!("maxChannels"), jni_sig!("()I"), &[])?.i()
        });
        match result {
            Ok(channels) if channels >= 2 => channels as u32,
            _ => 2,
        }
    }

    fn supports_encoded(&mut self, sample_rate: u32, channels: u32, codec: EncodedCodec) -> bool {
        let result = self.vm.attach_current_thread(|env| -> jni::errors::Result<bool> {
            env.call_method(
                &self.sink,
                jni_str!("supportsEncoded"),
                jni_sig!("(III)Z"),
                &[JValue::Int(sample_rate as i32), JValue::Int(channels as i32), JValue::Int(codec.encoding())],
            )?
            .z()
        });
        result.unwrap_or(false)
    }
}
