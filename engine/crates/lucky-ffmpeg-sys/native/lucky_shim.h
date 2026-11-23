#ifndef LUCKY_SHIM_H
#define LUCKY_SHIM_H

typedef void (*lucky_log_sink)(int level, const char *line);

void lucky_ffmpeg_set_log_sink(lucky_log_sink sink, int max_level);
const char *lucky_dav1d_version(void);

#endif
