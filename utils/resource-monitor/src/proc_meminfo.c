
#include "procfs.h"
#include "varint.h"

#include <memory.h>
#include <stdlib.h>
#include <stdio.h>

/**
 * Module data
 */
typedef struct {
	uint64_t mem_used;
	uint64_t mem_free;
	uint64_t mem_available;
	uint64_t swap_free;
} proc_meminfo_metrics;
#define DELTA(prev, curr, field) ((curr)->field - (prev)->field)

typedef struct {
	uint64_t mem_total;
	uint64_t swap_total;
	proc_meminfo_metrics *previous_metrics;
	proc_meminfo_metrics *current_metrics;
} proc_meminfo_data;


/**
 * Message writing logic
 */
typedef enum {
	TOTALS = 0,
	METRICS = 1
} proc_meminfo_msgtype;

#define WRITE_BUFFER_SIZE (128)
static char write_buffer[WRITE_BUFFER_SIZE];

static void write_totals(FILE *output_file, nanosec_t timestamp, proc_meminfo_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-meminfo: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-meminfo: Writing message type: %u\n", TOTALS & 0xFF);
	*buffer_ptr = (char)TOTALS;
	buffer_ptr++;

	DEBUG_PRINT("proc-meminfo: Writing memory and swap totals: %llu/%llu\n",
			data->mem_total, data->swap_total);
	*(uint64_t *)buffer_ptr = data->mem_total;
	buffer_ptr += sizeof(uint64_t);
	*(uint64_t *)buffer_ptr = data->swap_total;
	buffer_ptr += sizeof(uint64_t);

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

static void write_metrics(FILE *output_file, nanosec_t timestamp, proc_meminfo_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-meminfo: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-meminfo: Writing message type: %u\n", METRICS & 0xFF);
	*buffer_ptr = (char)METRICS;
	buffer_ptr++;

	int64_t delta_mem_used = (int64_t)DELTA(data->previous_metrics, data->current_metrics, mem_used);
	int64_t delta_mem_free = (int64_t)DELTA(data->previous_metrics, data->current_metrics, mem_free);
	int64_t delta_mem_available = (int64_t)DELTA(data->previous_metrics, data->current_metrics, mem_available);
	int64_t delta_swap_free = (int64_t)DELTA(data->previous_metrics, data->current_metrics, swap_free);
	DEBUG_PRINT("proc-meminfo: Writing metrics for memory (%lld/%lld/%lld) and swap (%lld)\n",
			delta_mem_used, delta_mem_free, delta_mem_available, delta_swap_free);
	write_var_int64_t(delta_mem_used, &buffer_ptr);
	write_var_int64_t(delta_mem_free, &buffer_ptr);
	write_var_int64_t(delta_mem_available, &buffer_ptr);
	write_var_int64_t(delta_swap_free, &buffer_ptr);

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}


/**
 * /proc/meminfo parsing logic
 */
#define READ_BUFFER_SIZE 256
static char read_buffer[READ_BUFFER_SIZE];

#define FIELD_BUFFERS      "Buffers"
#define FIELD_CACHED       "Cached"
#define FIELD_MEMAVAILABLE "MemAvailable"
#define FIELD_MEMFREE      "MemFree"
#define FIELD_MEMTOTAL     "MemTotal"
#define FIELD_SRECLAIMABLE "SReclaimable"
#define FIELD_SWAPFREE     "SwapFree"
#define FIELD_SWAPTOTAL    "SwapTotal"

static void parse_proc_meminfo(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();

	proc_meminfo_data *data = (proc_meminfo_data *)trace_file->data;
	
	FILE *file_handle = fopen(trace_file->source_file_name, "rb");
	// Read and parse until the end of the file to find the right memory statistics
	uint64_t mem_total;
	uint64_t swap_total;
	uint64_t buff_and_cache = 0;
	while (fgets(read_buffer, sizeof(read_buffer), file_handle) != NULL) {
		char field[64];
		uint64_t value;
		if (sscanf(read_buffer, "%63s %llu", field, &value) > 0) {
			// Trim the trailing colon
			field[strlen(field) - 1] = '\0';

			switch (field[0]) {
			case 'B':
				if (memcmp(field, FIELD_BUFFERS, sizeof(FIELD_BUFFERS)) == 0) {
					buff_and_cache += value;
				}
				break;
			case 'C':
				if (memcmp(field, FIELD_CACHED, sizeof(FIELD_CACHED)) == 0) {
					buff_and_cache += value;
				}
				break;
			case 'M':
				if (memcmp(field, FIELD_MEMAVAILABLE, sizeof(FIELD_MEMAVAILABLE)) == 0) {
					data->current_metrics->mem_available = value;
				} else if (memcmp(field, FIELD_MEMFREE, sizeof(FIELD_MEMFREE)) == 0) {
					data->current_metrics->mem_free = value;
				} else if (memcmp(field, FIELD_MEMTOTAL, sizeof(FIELD_MEMTOTAL)) == 0) {
					mem_total = value;
				}
				break;
			case 'S':
				if (memcmp(field, FIELD_SRECLAIMABLE, sizeof(FIELD_SRECLAIMABLE)) == 0) {
					buff_and_cache += value;
				} else if (memcmp(field, FIELD_SWAPFREE, sizeof(FIELD_SWAPFREE)) == 0) {
					data->current_metrics->swap_free = value;
				} else if (memcmp(field, FIELD_SWAPTOTAL, sizeof(FIELD_SWAPTOTAL)) == 0) {
					swap_total = value;
				}
				break;
			default:
				break;
			}
		}
	}

	// Compute and store mem_used
	data->current_metrics->mem_used = mem_total - data->current_metrics->mem_free - buff_and_cache;

	// If memory and/or swap totals changed, resend the totals
	if ((mem_total != data->mem_total) || (swap_total != data->swap_total)) {
		data->mem_total = mem_total;
		data->swap_total = swap_total;
		write_totals(trace_file->output_file, sample_time, data);
	}

	write_metrics(trace_file->output_file, sample_time, data);

	// Swap the metric buffers
	proc_meminfo_metrics *tmp = data->previous_metrics;
	data->previous_metrics = data->current_metrics;
	data->current_metrics = tmp;
}


/**
 * Parse module initialization and cleanup
 */
static const char proc_meminfo_filename[] = "/proc/meminfo";

static void cleanup_proc_meminfo(trace_file_t *trace_file) {
	fclose(trace_file->output_file);
	free(trace_file->data);
	free(trace_file);
}

trace_file_t *init_proc_meminfo_parser(const char *output_directory, const char *hostname) {
	char *output_filename = malloc(strlen(output_directory) + strlen("/proc-meminfo-") + strlen(hostname) + 1);
	*output_filename = '\0';
	strcat(output_filename, output_directory);
	strcat(output_filename, "/proc-meminfo-");
	strcat(output_filename, hostname);

	trace_file_t *trace_file = malloc(sizeof(trace_file_t));
	trace_file->parse_callback = parse_proc_meminfo;
	trace_file->cleanup_callback = cleanup_proc_meminfo;
	trace_file->source_file_name = proc_meminfo_filename;
	trace_file->data = calloc(1, sizeof(proc_meminfo_data) + 2 * sizeof(proc_meminfo_metrics));
	trace_file->output_file = fopen(output_filename, "wb");

	free(output_filename);

	((proc_meminfo_data *)trace_file->data)->previous_metrics =
		(proc_meminfo_metrics *)(trace_file->data + sizeof(proc_meminfo_data));
	((proc_meminfo_data *)trace_file->data)->current_metrics =
		(proc_meminfo_metrics *)(trace_file->data + sizeof(proc_meminfo_data) + sizeof(proc_meminfo_metrics));

	return trace_file;
}
