use std::ffi::c_void;
use std::sync::Arc;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::thread::JoinHandle;

type GetInstanceFn = unsafe extern "C" fn() -> *mut c_void;
type FrameCallback64 = unsafe extern "C" fn(i64, *mut c_void);
type FrameCallbackLong = unsafe extern "C" fn(libc::c_long, *mut c_void);
type PostCallback64Fn = unsafe extern "C" fn(*mut c_void, FrameCallback64, *mut c_void);
type PostCallbackLongFn = unsafe extern "C" fn(*mut c_void, FrameCallbackLong, *mut c_void);
type LooperPrepareFn = unsafe extern "C" fn(i32) -> *mut c_void;
type LooperPollFn = unsafe extern "C" fn(i32, *mut i32, *mut i32, *mut *mut c_void) -> i32;

#[derive(Clone, Copy)]
struct Functions {
    get_instance: GetInstanceFn,
    post64: Option<PostCallback64Fn>,
    post_long: Option<PostCallbackLongFn>,
    prepare: LooperPrepareFn,
    poll: LooperPollFn,
}

fn functions() -> Option<Functions> {
    static FUNCTIONS: OnceLock<Option<Functions>> = OnceLock::new();
    *FUNCTIONS.get_or_init(|| unsafe {
        let library = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD);
        if library.is_null() {
            return None;
        }
        let symbol = |name: &std::ffi::CStr| {
            let address = libc::dlsym(library, name.as_ptr());
            (!address.is_null()).then_some(address)
        };
        let get_instance = symbol(c"AChoreographer_getInstance")?;
        let prepare = symbol(c"ALooper_prepare")?;
        let poll = symbol(c"ALooper_pollOnce")?;
        let post64 = symbol(c"AChoreographer_postFrameCallback64");
        let post_long = if size_of::<libc::c_long>() == 8 { symbol(c"AChoreographer_postFrameCallback") } else { None };
        if post64.is_none() && post_long.is_none() {
            return None;
        }
        Some(Functions {
            get_instance: std::mem::transmute::<*mut c_void, GetInstanceFn>(get_instance),
            post64: post64.map(|a| std::mem::transmute::<*mut c_void, PostCallback64Fn>(a)),
            post_long: post_long.map(|a| std::mem::transmute::<*mut c_void, PostCallbackLongFn>(a)),
            prepare: std::mem::transmute::<*mut c_void, LooperPrepareFn>(prepare),
            poll: std::mem::transmute::<*mut c_void, LooperPollFn>(poll),
        })
    })
}

struct VsyncState {
    last_ns: AtomicI64,
    period_ns: AtomicI64,
    stop: AtomicBool,
    deltas: std::sync::Mutex<Vec<i64>>,
    functions: Functions,
    choreographer: AtomicI64,
}

impl VsyncState {
    fn record(&self, time_ns: i64) {
        let previous = self.last_ns.swap(time_ns, Ordering::AcqRel);
        if previous <= 0 {
            return;
        }
        let delta = time_ns - previous;
        if !(2_000_000..=50_000_000).contains(&delta) {
            return;
        }
        let mut deltas = self.deltas.lock().unwrap_or_else(|e| e.into_inner());
        deltas.push(delta);
        if deltas.len() > 15 {
            deltas.remove(0);
        }
        if deltas.len() >= 5 {
            let mut sorted = deltas.clone();
            sorted.sort_unstable();
            self.period_ns.store(sorted[sorted.len() / 2], Ordering::Release);
        }
    }

    unsafe fn post(state: *const VsyncState) {
        unsafe {
            let this = &*state;
            let choreographer = this.choreographer.load(Ordering::Acquire) as *mut c_void;
            if choreographer.is_null() || this.stop.load(Ordering::Acquire) {
                return;
            }
            if let Some(post) = this.functions.post64 {
                post(choreographer, on_frame64, state as *mut c_void);
            } else if let Some(post) = this.functions.post_long {
                post(choreographer, on_frame_long, state as *mut c_void);
            }
        }
    }
}

unsafe extern "C" fn on_frame64(time_ns: i64, data: *mut c_void) {
    unsafe {
        let state = data as *const VsyncState;
        (*state).record(time_ns);
        VsyncState::post(state);
    }
}

unsafe extern "C" fn on_frame_long(time_ns: libc::c_long, data: *mut c_void) {
    unsafe { on_frame64(time_ns as i64, data) }
}

pub struct VsyncTracker {
    state: Arc<VsyncState>,
    thread: Option<JoinHandle<()>>,
}

impl VsyncTracker {
    pub fn start() -> Option<Self> {
        let functions = functions()?;
        let state = Arc::new(VsyncState {
            last_ns: AtomicI64::new(0),
            period_ns: AtomicI64::new(0),
            stop: AtomicBool::new(false),
            deltas: std::sync::Mutex::new(Vec::new()),
            functions,
            choreographer: AtomicI64::new(0),
        });
        let worker = state.clone();
        let thread = std::thread::Builder::new()
            .name("lucky-vsync".into())
            .spawn(move || unsafe {
                (worker.functions.prepare)(0);
                let choreographer = (worker.functions.get_instance)();
                if choreographer.is_null() {
                    return;
                }
                worker.choreographer.store(choreographer as i64, Ordering::Release);
                VsyncState::post(Arc::as_ptr(&worker));
                while !worker.stop.load(Ordering::Acquire) {
                    (worker.functions.poll)(100, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
                }
            })
            .ok()?;
        Some(Self { state, thread: Some(thread) })
    }

    pub fn timing(&self) -> Option<(i64, i64)> {
        let last = self.state.last_ns.load(Ordering::Acquire);
        let period = self.state.period_ns.load(Ordering::Acquire);
        (last > 0 && period > 0).then_some((last, period))
    }
}

impl Drop for VsyncTracker {
    fn drop(&mut self) {
        self.state.stop.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
