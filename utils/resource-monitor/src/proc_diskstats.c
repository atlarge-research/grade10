
#include "procfs.h"
#include "varint.h"

#include <memory.h>
#include <stdlib.h>
#include <stdio.h>

/**
 * Module data
 */
typedef struct {
	uint64_t read_completed;
	uint64_t read_sectors;
	uint64_t read_time_ms;
	uint64_t write_completed;
	uint64_t write_sectors;
	uint64_t write_time_ms;
	uint64_t io_time_ms;
} proc_diskstats_metrics;
#define DELTA(prev, curr, field) ((curr)->field - (prev)->field)

typedef struct {
	unsigned int num_disks;
	char **disk_names;
	proc_diskstats_metrics *previous_metrics;
	proc_diskstats_metrics *current_metrics;
} proc_diskstats_data;

static void cleanup_data_buffers(proc_diskstats_data *data) {
	if (data->disk_names != NULL) {
		for (unsigned int i = 0; i < data->num_disks; i++) {
			free(data->disk_names[i]);
		}
		free(data->disk_names);
		data->disk_names = NULL;
	}
	if (data->previous_metrics != NULL) {
		free(data->previous_metrics);
		data->previous_metrics = NULL;
	}
	if (data->current_metrics != NULL) {
		free(data->current_metrics);
		data->current_metrics = NULL;
	}
}


/**
 * Message writing logic
 */
typedef enum {
	DISK_LIST = 0,
	METRICS = 1
} proc_diskstats_msgtype;

#define WRITE_BUFFER_SIZE (4 * 4096)
static char write_buffer[WRITE_BUFFER_SIZE];

static void write_disk_list(FILE *output_file, nanosec_t timestamp, proc_diskstats_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-diskstats: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-diskstats: Writing message type: %u\n", DISK_LIST & 0xFF);
	*buffer_ptr = (char)DISK_LIST;
	buffer_ptr++;

	DEBUG_PRINT("proc-diskstats: Writing num disks: %u\n", data->num_disks);
	write_var_uint32_t(data->num_disks, &buffer_ptr);

	for (unsigned int disk_id = 0; disk_id < data->num_disks; disk_id++) {
		DEBUG_PRINT("proc-diskstats: Writing disk name: %s\n", data->disk_names[disk_id]);
		int disk_name_len = strlen(data->disk_names[disk_id]);
		if ((size_t)(end_of_buffer - buffer_ptr) < disk_name_len + 1) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}
		memcpy(buffer_ptr, data->disk_names[disk_id], disk_name_len + 1);
		buffer_ptr += disk_name_len + 1;
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

static void write_metrics(FILE *output_file, nanosec_t timestamp, proc_diskstats_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-diskstats: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-diskstats: Writing message type: %u\n", METRICS & 0xFF);
	*buffer_ptr = (char)METRICS;
	buffer_ptr++;

	DEBUG_PRINT("proc-diskstats: Writing num disks: %u\n", data->num_disks);
	write_var_uint32_t(data->num_disks, &buffer_ptr);

	proc_diskstats_metrics *prev = data->previous_metrics;
	proc_diskstats_metrics *curr = data->current_metrics;
	for (unsigned int disk_id = 0; disk_id < data->num_disks; disk_id++) {
		if ((size_t)(end_of_buffer - buffer_ptr) < 60) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}

		uint64_t delta_read_completed = DELTA(prev, curr, read_completed);
		uint64_t delta_read_sectors = DELTA(prev, curr, read_sectors);
		uint64_t delta_read_time_ms = DELTA(prev, curr, read_time_ms);
		uint64_t delta_write_completed = DELTA(prev, curr, write_completed);
		uint64_t delta_write_sectors = DELTA(prev, curr, write_sectors);
		uint64_t delta_write_time_ms = DELTA(prev, curr, write_time_ms);
		uint64_t delta_io_time_ms = DELTA(prev, curr, io_time_ms);
		DEBUG_PRINT("proc-diskstats: Writing read (%llu/%llu/%llu), write (%llu/%llu/%llu), and total (%llu) stats for disk %s\n",
				delta_read_completed, delta_read_sectors, delta_read_time_ms,
				delta_write_completed, delta_write_sectors, delta_write_time_ms,
				delta_io_time_ms, data->disk_names[disk_id]);

		write_var_uint64_t(delta_read_completed, &buffer_ptr);
		write_var_uint64_t(delta_read_sectors, &buffer_ptr);
		write_var_uint64_t(delta_read_time_ms, &buffer_ptr);
		write_var_uint64_t(delta_write_completed, &buffer_ptr);
		write_var_uint64_t(delta_write_sectors, &buffer_ptr);
		write_var_uint64_t(delta_write_time_ms, &buffer_ptr);
		write_var_uint64_t(delta_io_time_ms, &buffer_ptr);

		prev++;
		curr++;
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}


/**
 * /proc/diskstats parsing logic
 */
#define READ_BUFFER_SIZE (4096)
static char read_buffer[READ_BUFFER_SIZE];

#define MAX_DISK_NAME_SIZE 255
#define STR(x) STR1(x)
#define STR1(x) #x

static void enumerate_disks(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();

	FILE *file_handle = fopen(trace_file->source_file_name, "rb");
	// Read and parse until the end of the file to find all disk names
	char disk_names_count = 0;
	char disk_names_length = 8;
	char **disk_names = malloc(sizeof(char *) * disk_names_length);
	char *disk_name = malloc(MAX_DISK_NAME_SIZE + 1);
	while (fscanf(file_handle, " %*d %*d %" STR(MAX_DISK_NAME_SIZE) "s"
				" %*llu %*llu %*llu %*llu %*llu %*llu %*llu %*llu"
				" %*llu %*llu %*llu", disk_name) > 0) {
		if (disk_names_count == disk_names_length) {
			disk_names = realloc(disk_names, sizeof(char *) * disk_names_length * 2);
			disk_names_length *= 2;
		}
		disk_names[disk_names_count] = disk_name;
		disk_names_count++;

		disk_name = malloc(MAX_DISK_NAME_SIZE + 1);
	}
	fclose(file_handle);


	// Clean up existing data structures
	proc_diskstats_data *data = (proc_diskstats_data *)trace_file->data;
	cleanup_data_buffers(data);
	// Store enumerated disks and reallocate data structures
	data->num_disks = disk_names_count;
	data->disk_names = disk_names;
	data->previous_metrics = calloc(disk_names_count, sizeof(proc_diskstats_metrics));
	data->current_metrics = calloc(disk_names_count, sizeof(proc_diskstats_metrics));


	// Write the disk list to the output file
	write_disk_list(trace_file->output_file, sample_time, data);
}

static void parse_proc_diskstats(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();

	proc_diskstats_data *data = (proc_diskstats_data *)trace_file->data;

	FILE *file_handle = fopen(trace_file->source_file_name, "rb");
	// Read and parse until the end of the file to find all disk statistics
	proc_diskstats_metrics *next_disk = data->current_metrics;
	char disk_name[MAX_DISK_NAME_SIZE + 1];
	unsigned int disk_id = 0;
	uint64_t read_completed, read_sectors, read_time_ms, write_completed, write_sectors, write_time_ms, io_time_ms;
	while (fscanf(file_handle, " %*d %*d %" STR(MAX_DISK_NAME_SIZE) "s"
				" %llu %*llu %llu %llu %llu %*llu %llu %llu"
				" %*llu %llu %*llu", disk_name,
				&read_completed, &read_sectors, &read_time_ms,
				&write_completed, &write_sectors, &write_time_ms,
				&io_time_ms) > 0) {
		if (disk_id >= data->num_disks) {
			// Number of disks has changed, so re-enumerate all disks
			fclose(file_handle);
			enumerate_disks(trace_file);
			return;
		}

		// Ensure that the disk name matches the cached name
		if (strcmp(disk_name, data->disk_names[disk_id]) != 0) {
			// Disk name does not match, so re-enumerate all disks
			fclose(file_handle);
			enumerate_disks(trace_file);
			return;
		}

		// Store parsed values
		next_disk->read_completed = read_completed;
		next_disk->read_sectors = read_sectors;
		next_disk->read_time_ms = read_time_ms;
		next_disk->write_completed = write_completed;;
		next_disk->write_sectors = write_sectors;
		next_disk->write_time_ms = write_time_ms;
		next_disk->io_time_ms = io_time_ms;

		next_disk++;
		disk_id++;
	}
	if (disk_id != data->num_disks) {
		// Number of disks has changed, so re-enumerate all disks
		fclose(file_handle);
		enumerate_disks(trace_file);
		return;
	}
	fclose(file_handle);

	// Write metrics to the output file
	write_metrics(trace_file->output_file, sample_time, data);

	// Swap the metric buffers
	proc_diskstats_metrics *tmp = data->previous_metrics;
	data->previous_metrics = data->current_metrics;
	data->current_metrics = tmp;
}


/**
 * Parse module initialization and cleanup
 */
static const char proc_diskstats_filename[] = "/proc/diskstats";

static void cleanup_proc_diskstats(trace_file_t *trace_file) {
	fclose(trace_file->output_file);
	cleanup_data_buffers((proc_diskstats_data *)trace_file->data);
	free(trace_file->data);
	free(trace_file);
}

trace_file_t *init_proc_diskstats_parser(const char *output_directory, const char *hostname) {
	char *output_filename = malloc(strlen(output_directory) + strlen("/proc-diskstats-") + strlen(hostname) + 1);
	*output_filename = '\0';
	strcat(output_filename, output_directory);
	strcat(output_filename, "/proc-diskstats-");
	strcat(output_filename, hostname);

	trace_file_t *trace_file = malloc(sizeof(trace_file_t));
	trace_file->parse_callback = parse_proc_diskstats;
	trace_file->cleanup_callback = cleanup_proc_diskstats;
	trace_file->source_file_name = proc_diskstats_filename;
	trace_file->data = calloc(1, sizeof(proc_diskstats_data));
	trace_file->output_file = fopen(output_filename, "wb");

	free(output_filename);

	enumerate_disks(trace_file);

	return trace_file;
}
