
#include "monitor.h"
#include "procfs.h"

#include <memory.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>


/**
 * Global settings for the monitor.
 */
typedef struct {
	nanosec_t monitor_period;
	const char *output_directory;
} monitor_settings_t;
static const nanosec_t DEFAULT_MONITOR_PERIOD = 100 * MILLISECONDS;
static const char DEFAULT_OUTPUT_DIRECTORY[] = ".";

monitor_settings_t parse_settings(int argc, char **argv) {
	monitor_settings_t settings = {
		.monitor_period = DEFAULT_MONITOR_PERIOD,
		.output_directory = DEFAULT_OUTPUT_DIRECTORY
	};
	return settings;
}

/**
 * Catch SIGINT and set a flag to stop the main monitoring loop.
 */
volatile bool interrupted = false;

void sigint_handler(int signum) {
	interrupted = true;
}

void setup_sigint_handler() {
	signal(SIGINT, sigint_handler);
}

/**
 * Sleep until a given moment in time.
 */
void sleep_until(nanosec_t wake_up_time) {
	nanosec_t current_time = get_time();
	if (current_time < wake_up_time) {
		nanosec_t sleep_time = wake_up_time - current_time;
		struct timespec sleep_timespec = {
			.tv_sec = sleep_time / SECONDS,
			.tv_nsec = (sleep_time % SECONDS) / NANOSECONDS
		};
		nanosleep(&sleep_timespec, NULL);
	}
}

/**
 * Management of the monitor_state_t.trace_files list.
 */
void init_trace_file(trace_file_t **trace_file_out, void (*parse_callback)(trace_file_t *), char *source_file_name) {
	trace_file_t *trace_file = malloc(sizeof(trace_file_t) + strlen(source_file_name) + 1);
	trace_file->parse_callback = parse_callback;
	trace_file->source_file_name = (char *)(((size_t)trace_file) + sizeof(trace_file_t));
	memcpy(source_file_name, trace_file->source_file_name, strlen(source_file_name) + 1);
}

void add_trace_file(monitor_state_t *monitor_state, trace_file_t *trace_file) {
	trace_file->next = monitor_state->trace_files;
	monitor_state->trace_files = trace_file;
	monitor_state->trace_file_count++;
}

/**
 * Management of the monitor state
 */
monitor_state_t init_state(monitor_settings_t *settings, int argc, char **argv) {
	monitor_state_t res = { .trace_files = NULL, .trace_file_count = 0 };
	return res;
}

/**
 * Initialization
 */
void init_all_parsers(monitor_settings_t *settings, monitor_state_t *state) {
	char hostname[256];
	gethostname(hostname, sizeof(hostname));
	hostname[255] = '\0';

	add_trace_file(state, init_proc_stat_parser(settings->output_directory, hostname));
	add_trace_file(state, init_proc_net_dev_parser(settings->output_directory, hostname));
	add_trace_file(state, init_proc_diskstats_parser(settings->output_directory, hostname));
	add_trace_file(state, init_proc_meminfo_parser(settings->output_directory, hostname));
}


int main(int argc, char **argv) {
	setup_sigint_handler();
	monitor_settings_t settings = parse_settings(argc, argv);
	monitor_state_t state = init_state(&settings, argc, argv);

	init_all_parsers(&settings, &state);

	nanosec_t last_update_time;
	while (!interrupted) {
		last_update_time = get_time();
		DEBUG_PRINT("Monitoring at t=%llu\n", last_update_time);

		for (trace_file_t *trace_file = state.trace_files; trace_file != NULL; trace_file = trace_file->next) {
			trace_file->parse_callback(trace_file);
		}

		sleep_until(last_update_time + settings.monitor_period);
	}
}
