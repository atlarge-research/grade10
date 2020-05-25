
#ifndef __MONITOR_H__
#define __MONITOR_H__

#include <stdio.h>
#include <time.h>

/**
 * Debug helpers
 */
#ifdef DEBUG
	#define DEBUG_PRINT(...) printf(__VA_ARGS__)
#else
	#define DEBUG_PRINT(...) do {} while (0)
#endif


/**
 * Time representation
 */
typedef long long nanosec_t;
#define NANOSECONDS  (1LL)
#define MICROSECONDS (1000 * NANOSECONDS)
#define MILLISECONDS (1000 * MICROSECONDS)
#define SECONDS      (1000 * MILLISECONDS)

static inline nanosec_t get_time() {
	struct timespec current_time;
	clock_gettime(CLOCK_REALTIME, &current_time);
	return current_time.tv_sec * SECONDS + current_time.tv_nsec * NANOSECONDS;
}



/**
 * Monitor state
 */
typedef struct trace_file_t trace_file_t;
struct trace_file_t {
	void (*parse_callback)(trace_file_t *);
	void (*cleanup_callback)(trace_file_t *);
	const char *source_file_name;
	FILE *output_file;
	void *data;

	trace_file_t *next;
};

typedef struct {
	trace_file_t *trace_files;
	int trace_file_count;
} monitor_state_t;

#endif

