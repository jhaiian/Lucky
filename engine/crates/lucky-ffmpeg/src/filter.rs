use std::ffi::{CStr, c_void};
use std::os::raw::c_int;
use std::ptr;

use lucky_ffmpeg_sys as sys;

use crate::error::{Error, Result, check};
use crate::frame::Frame;

const BUFFERSRC_KEEP_REF: c_int = 1 << 3;
const MAX_PULL: usize = 32;

#[derive(Clone, Copy, PartialEq, Eq)]
struct Signature {
    width: i32,
    height: i32,
    format: i32,
}

impl Signature {
    fn of(frame: &Frame) -> Self {
        Self { width: frame.width(), height: frame.height(), format: frame.format() }
    }
}

struct Graph {
    graph: *mut sys::AVFilterGraph,
    source: *mut sys::AVFilterContext,
    filter: *mut sys::AVFilterContext,
    sink: *mut sys::AVFilterContext,
    input_time_base: sys::AVRational,
    output_time_base: sys::AVRational,
}

fn allocate(graph: *mut sys::AVFilterGraph, kind: &CStr, name: &CStr) -> Result<*mut sys::AVFilterContext> {
    unsafe {
        let definition = sys::avfilter_get_by_name(kind.as_ptr());
        if definition.is_null() {
            return Err(Error::Other(format!("the {} filter is not built in", kind.to_string_lossy())));
        }
        let context = sys::avfilter_graph_alloc_filter(graph, definition, name.as_ptr());
        if context.is_null() {
            return Err(Error::OutOfMemory("creating the deinterlacer"));
        }
        Ok(context)
    }
}

impl Graph {
    fn build(frame: &Frame, time_base: sys::AVRational, threads: i32, fields: bool) -> Result<Self> {
        unsafe {
            let graph = sys::avfilter_graph_alloc();
            if graph.is_null() {
                return Err(Error::OutOfMemory("creating the deinterlacer"));
            }
            let mut built = Graph {
                graph,
                source: ptr::null_mut(),
                filter: ptr::null_mut(),
                sink: ptr::null_mut(),
                input_time_base: time_base,
                output_time_base: time_base,
            };
            (*graph).nb_threads = threads.max(0);

            built.source = allocate(graph, c"buffer", c"in")?;
            let params = sys::av_buffersrc_parameters_alloc();
            if params.is_null() {
                return Err(Error::OutOfMemory("creating the deinterlacer"));
            }
            let raw = frame.raw();
            (*params).format = raw.format;
            (*params).width = raw.width;
            (*params).height = raw.height;
            (*params).time_base = time_base;
            (*params).sample_aspect_ratio = raw.sample_aspect_ratio;
            (*params).color_space = raw.colorspace;
            (*params).color_range = raw.color_range;
            let code = sys::av_buffersrc_parameters_set(built.source, params);
            sys::av_free(params as *mut c_void);
            check("configuring the deinterlacer input", code)?;
            check("starting the deinterlacer input", sys::avfilter_init_str(built.source, ptr::null()))?;

            built.filter = allocate(graph, c"bwdif", c"deint")?;
            let options = if fields {
                c"mode=send_field:parity=auto:deint=all"
            } else {
                c"mode=send_frame:parity=auto:deint=all"
            };
            check("starting the deinterlacer", sys::avfilter_init_str(built.filter, options.as_ptr()))?;

            built.sink = allocate(graph, c"buffersink", c"out")?;
            check("starting the deinterlacer output", sys::avfilter_init_str(built.sink, ptr::null()))?;

            check("linking the deinterlacer", sys::avfilter_link(built.source, 0, built.filter, 0))?;
            check("linking the deinterlacer", sys::avfilter_link(built.filter, 0, built.sink, 0))?;
            check("configuring the deinterlacer", sys::avfilter_graph_config(graph, ptr::null_mut()))?;
            built.output_time_base = sys::av_buffersink_get_time_base(built.sink);
            Ok(built)
        }
    }

    fn set_fields(&mut self, fields: bool) -> bool {
        let value = if fields { c"send_field" } else { c"send_frame" };
        let code = unsafe {
            sys::av_opt_set(
                self.filter as *mut c_void,
                c"mode".as_ptr(),
                value.as_ptr(),
                sys::AV_OPT_SEARCH_CHILDREN as c_int,
            )
        };
        code >= 0
    }

    fn push(&mut self, frame: &Frame) -> Result<()> {
        let code = unsafe { sys::av_buffersrc_add_frame_flags(self.source, frame.as_ptr() as *mut sys::AVFrame, BUFFERSRC_KEEP_REF) };
        check("deinterlacing a frame", code)?;
        Ok(())
    }

    fn close(&mut self) {
        unsafe { sys::av_buffersrc_add_frame_flags(self.source, ptr::null_mut(), 0) };
    }

    fn collect(&mut self, frames: &mut Vec<Frame>) {
        for _ in 0..MAX_PULL {
            let Ok(mut frame) = Frame::new() else {
                return;
            };
            let code = unsafe { sys::av_buffersink_get_frame(self.sink, frame.as_mut_ptr()) };
            if code < 0 {
                if code != sys::AVERROR_EAGAIN && code != sys::AVERROR_EOF {
                    log::warn!("deinterlacer output failed: {}", crate::error::error_string(code));
                }
                return;
            }
            self.restamp(&mut frame);
            frames.push(frame);
        }
    }

    fn restamp(&self, frame: &mut Frame) {
        unsafe {
            let raw = &mut *frame.as_mut_ptr();
            if raw.pts != sys::AV_NOPTS_VALUE {
                raw.pts = sys::av_rescale_q(raw.pts, self.output_time_base, self.input_time_base);
            }
            raw.best_effort_timestamp = raw.pts;
            if raw.duration > 0 {
                raw.duration = sys::av_rescale_q(raw.duration, self.output_time_base, self.input_time_base);
            }
        }
    }
}

impl Drop for Graph {
    fn drop(&mut self) {
        unsafe { sys::avfilter_graph_free(&mut self.graph) };
    }
}

pub struct Deinterlacer {
    graph: Option<Graph>,
    signature: Option<Signature>,
    time_base: sys::AVRational,
    threads: i32,
    fields: bool,
    backlog: Vec<Frame>,
}

unsafe impl Send for Deinterlacer {}

impl Deinterlacer {
    pub fn new(time_base: sys::AVRational, threads: i32) -> Self {
        Self { graph: None, signature: None, time_base, threads, fields: true, backlog: Vec::new() }
    }

    pub fn active(&self) -> bool {
        self.graph.is_some()
    }

    pub fn fields(&self) -> bool {
        self.fields
    }

    pub fn set_fields(&mut self, fields: bool) {
        if self.fields == fields {
            return;
        }
        self.fields = fields;
        let applied = self.graph.as_mut().is_none_or(|graph| graph.set_fields(fields));
        if !applied {
            self.retire();
        }
    }

    pub fn push(&mut self, frame: &Frame) -> Result<()> {
        let signature = Signature::of(frame);
        if self.signature != Some(signature) {
            self.retire();
        }
        if self.graph.is_none() {
            self.graph = Some(Graph::build(frame, self.time_base, self.threads, self.fields)?);
            self.signature = Some(signature);
        }
        match self.graph.as_mut() {
            Some(graph) => graph.push(frame),
            None => Ok(()),
        }
    }

    pub fn pull(&mut self) -> Vec<Frame> {
        let mut frames = std::mem::take(&mut self.backlog);
        if let Some(graph) = self.graph.as_mut() {
            graph.collect(&mut frames);
        }
        frames
    }

    pub fn flush(&mut self) {
        self.retire();
    }

    pub fn reset(&mut self) {
        self.graph = None;
        self.signature = None;
        self.backlog.clear();
    }

    fn retire(&mut self) {
        if let Some(mut graph) = self.graph.take() {
            graph.close();
            graph.collect(&mut self.backlog);
        }
        self.signature = None;
    }
}
