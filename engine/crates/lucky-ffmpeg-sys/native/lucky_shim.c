#include <stdarg.h>
#include <stdio.h>
#include <libavutil/log.h>
#include <dav1d/dav1d.h>
#include "lucky_shim.h"

static lucky_log_sink current_sink = 0;
static int current_max_level = AV_LOG_WARNING;

static void lucky_log_callback(void *avcl, int level, const char *fmt, va_list vl)
{
    static _Thread_local int print_prefix = 1;
    char line[1024];
    lucky_log_sink sink = current_sink;
    if (!sink || level > current_max_level)
        return;
    av_log_format_line2(avcl, level, fmt, vl, line, sizeof(line), &print_prefix);
    sink(level, line);
}

void lucky_ffmpeg_set_log_sink(lucky_log_sink sink, int max_level)
{
    current_max_level = max_level;
    current_sink = sink;
    av_log_set_level(max_level);
    av_log_set_callback(sink ? lucky_log_callback : av_log_default_callback);
}

const char *lucky_dav1d_version(void)
{
    return dav1d_version();
}
