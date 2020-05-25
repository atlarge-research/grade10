
#include "procfs.h"
#include "varint.h"

#include <memory.h>
#include <stdlib.h>
#include <stdio.h>

/**
 * Data structures representing the relevant information of /proc/stat
 */
typedef struct {
	uint64_t user;
	uint64_t nice;
	uint64_t system;
	uint64_t idle;
	uint64_t iowait;
	uint64_t irq;
	uint64_t softirq;
	uint64_t steal;
	uint64_t guest;
	uint64_t guestnice;
} proc_stat_cpus_data;

typedef struct {
	unsigned int num_cpus;
	proc_stat_cpus_data cpu_infos[];
} proc_stat_data;

static proc_stat_data *alloc_proc_stat_data(unsigned int num_cpus) {
	proc_stat_data *res = calloc(1, sizeof(proc_stat_data) +
			sizeof(proc_stat_cpus_data) * num_cpus);
	res->num_cpus = num_cpus;
	return res;
}


/**
 * /proc/stat parsing logic
 */
#define WRITE_BUFFER_SIZE (4 * 4096)
static char write_buffer[WRITE_BUFFER_SIZE];
static proc_stat_data *temp_data;

static void read_proc_stat(const char *filename, proc_stat_data *out_data) {
	// Format: first line contains aggregate numbers (skip), next <num_cpus>
	// lines contain "cpuXX" followed by 10 values, remaining lines are skipped
	
	FILE *file_handle = fopen(filename, "rb");
	// Read and skip first line
	char buffer[255];
	fgets(buffer, sizeof(buffer), file_handle);
	// Read and parse next <num_cpus> lines
	for (unsigned int cpu_id = 0; cpu_id < out_data->num_cpus; cpu_id++) {
		fscanf(file_handle, "cpu%*d %llu %llu %llu %llu %llu %llu %llu %llu %llu %llu ",
				&out_data->cpu_infos[cpu_id].user,
				&out_data->cpu_infos[cpu_id].nice,
				&out_data->cpu_infos[cpu_id].system,
				&out_data->cpu_infos[cpu_id].idle,
				&out_data->cpu_infos[cpu_id].iowait,
				&out_data->cpu_infos[cpu_id].irq,
				&out_data->cpu_infos[cpu_id].softirq,
				&out_data->cpu_infos[cpu_id].steal,
				&out_data->cpu_infos[cpu_id].guest,
				&out_data->cpu_infos[cpu_id].guestnice);
	}
	// Skip remaining lines
	fclose(file_handle);
}

static void write_deltas(FILE *output_file, nanosec_t timestamp, proc_stat_data *deltas) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-stat: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-stat: Writing num cpus: %u\n", deltas->num_cpus);
	write_var_uint32_t(deltas->num_cpus, &buffer_ptr);
	for (unsigned int cpu_id = 0; cpu_id < deltas->num_cpus; cpu_id++) {
		if ((size_t)(end_of_buffer - buffer_ptr) < 100) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}

		uint64_t *cpu_data = (uint64_t *)&deltas->cpu_infos[cpu_id];
		for (unsigned int field = 0; field < 10; field++) {
			DEBUG_PRINT("proc-stat: Writing cpu %u, field %u: %llu\n", cpu_id, field, cpu_data[field]);
			write_var_uint32_t(cpu_data[field], &buffer_ptr);
		}
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

static void parse_proc_stat(trace_file_t *trace_file) {
	proc_stat_data *previous_data = (proc_stat_data *)trace_file->data;
	proc_stat_data *current_data = temp_data;

	nanosec_t sample_time = get_time();
	read_proc_stat(trace_file->source_file_name, current_data);

	// Let previous_data = current_data - previous_data to prepare for writing deltas
	for (unsigned int cpu_id = 0; cpu_id < current_data->num_cpus; cpu_id++) {
		uint64_t *current_cpu_data = (uint64_t *)&current_data->cpu_infos[cpu_id];
		uint64_t *previous_cpu_data = (uint64_t *)&previous_data->cpu_infos[cpu_id];
		for (unsigned int field_id = 0; field_id < 10; field_id++) {
			previous_cpu_data[field_id] = current_cpu_data[field_id] - previous_cpu_data[field_id];
		}
	}

	write_deltas(trace_file->output_file, sample_time, previous_data);

	// Swap buffers for next iteration
	trace_file->data = current_data;
	temp_data = previous_data;
}


/**
 * Parse module initialization
 */
static const char proc_stat_filename[] = "/proc/stat";

static unsigned int count_num_cpus() {
	FILE *stat_file = fopen(proc_stat_filename, "rb");
	// Skip the first line
	char buffer[255];
	fgets(buffer, sizeof(buffer), stat_file);
	// Attempt to scan lines that match cpu info and count the matching lines
	unsigned int num_cpus = 0;
	unsigned int cpu_id;
	while (fscanf(stat_file, "cpu%d %*s %*s %*s %*s %*s %*s %*s %*s %*s %*s ", &cpu_id) > 0) {
		num_cpus++;
	}

	return num_cpus;
}

static void cleanup_proc_stat(trace_file_t *trace_file) {
	free(temp_data);
	free(trace_file->data);
	fclose(trace_file->output_file);
	free(trace_file);
}

trace_file_t *init_proc_stat_parser(const char *output_directory, const char *hostname) {
	unsigned int num_cpus = count_num_cpus();
	temp_data = alloc_proc_stat_data(num_cpus);

	char *output_filename = malloc(strlen(output_directory) + strlen("/proc-stat-") + strlen(hostname) + 1);
	*output_filename = '\0';
	strcat(output_filename, output_directory);
	strcat(output_filename, "/proc-stat-");
	strcat(output_filename, hostname);

	trace_file_t *trace_file = malloc(sizeof(trace_file_t));
	trace_file->parse_callback = parse_proc_stat;
	trace_file->cleanup_callback = cleanup_proc_stat;
	trace_file->source_file_name = proc_stat_filename;
	trace_file->data = alloc_proc_stat_data(num_cpus);
	trace_file->output_file = fopen(output_filename, "wb");

	free(output_filename);

	return trace_file;
}
