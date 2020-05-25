
#include "procfs.h"
#include "varint.h"

#include <memory.h>
#include <stdlib.h>
#include <stdio.h>


/**
 * Module data
 */
typedef struct {
	uint64_t recv_bytes;
	uint64_t recv_packets;
	uint64_t send_bytes;
	uint64_t send_packets;
} proc_net_dev_iface_metrics;

typedef struct {
	unsigned int num_ifaces;
	char **iface_names;
	proc_net_dev_iface_metrics *previous_metrics;
	proc_net_dev_iface_metrics *current_metrics;
} proc_net_dev_data;

static void cleanup_data_buffers(proc_net_dev_data *data) {
	if (data->iface_names != NULL) {
		for (unsigned int i = 0; i < data->num_ifaces; i++) {
			free(data->iface_names[i]);
		}
		free(data->iface_names);
		data->iface_names = NULL;
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
	IFACE_LIST = 0,
	METRICS = 1
} proc_net_dev_msgtype;

#define WRITE_BUFFER_SIZE (4 * 4096)
static char write_buffer[WRITE_BUFFER_SIZE];

static void write_iface_list(FILE *output_file, nanosec_t timestamp, proc_net_dev_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-net-dev: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-net-dev: Writing message type: %u\n", IFACE_LIST & 0xFF);
	*buffer_ptr = (char)IFACE_LIST;
	buffer_ptr++;

	DEBUG_PRINT("proc-net-dev: Writing num interfaces: %u\n", data->num_ifaces);
	write_var_uint32_t(data->num_ifaces, &buffer_ptr);

	for (unsigned int iface_id = 0; iface_id < data->num_ifaces; iface_id++) {
		DEBUG_PRINT("proc-net-dev: Writing interface name: %s\n", data->iface_names[iface_id]);
		int iface_name_len = strlen(data->iface_names[iface_id]);
		if ((size_t)(end_of_buffer - buffer_ptr) < iface_name_len + 1) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}
		memcpy(buffer_ptr, data->iface_names[iface_id], iface_name_len + 1);
		buffer_ptr += iface_name_len + 1;
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}

static void write_metrics(FILE *output_file, nanosec_t timestamp, proc_net_dev_data *data) {
	char *buffer_ptr = write_buffer;
	char *end_of_buffer = write_buffer + sizeof(write_buffer);

	DEBUG_PRINT("proc-net-dev: Writing timestamp: %llu\n", timestamp);
	*(nanosec_t *)buffer_ptr = timestamp;
	buffer_ptr += sizeof(nanosec_t);

	DEBUG_PRINT("proc-net-dev: Writing message type: %u\n", METRICS & 0xFF);
	*buffer_ptr = (char)METRICS;
	buffer_ptr++;

	DEBUG_PRINT("proc-net-dev: Writing num interfaces: %u\n", data->num_ifaces);
	write_var_uint32_t(data->num_ifaces, &buffer_ptr);

	proc_net_dev_iface_metrics *prev = data->previous_metrics;
	proc_net_dev_iface_metrics *curr = data->current_metrics;
	for (unsigned int iface_id = 0; iface_id < data->num_ifaces; iface_id++) {
		if ((size_t)(end_of_buffer - buffer_ptr) < 40) {
			fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
			buffer_ptr = write_buffer;
		}

		uint64_t d_rb = curr->recv_bytes - prev->recv_bytes;
		uint64_t d_rp = curr->recv_packets - prev->recv_packets;
		uint64_t d_sb = curr->send_bytes - prev->send_bytes;
		uint64_t d_sp = curr->send_packets - prev->send_packets;
		DEBUG_PRINT("proc-net-dev: Writing recv (%llu/%llu) and send (%llu/%llu)\n", d_rb, d_rp, d_sb, d_sp);

		write_var_uint64_t(d_rb, &buffer_ptr);
		write_var_uint64_t(d_rp, &buffer_ptr);
		write_var_uint64_t(d_sb, &buffer_ptr);
		write_var_uint64_t(d_sp, &buffer_ptr);

		prev++;
		curr++;
	}

	fwrite(write_buffer, (size_t)(buffer_ptr - write_buffer), 1, output_file);
}


/**
 * /proc/net/dev parsing logic
 */
#define READ_BUFFER_SIZE (4096)
static char read_buffer[READ_BUFFER_SIZE];

#define MAX_IFACE_NAME_SIZE 255
#define STR(x) STR1(x)
#define STR1(x) #x

static void enumerate_interfaces(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();

	FILE *file_handle = fopen(trace_file->source_file_name, "rb");
	// Read and skip the first two lines (headers)
	fgets(read_buffer, sizeof(read_buffer), file_handle);
	fgets(read_buffer, sizeof(read_buffer), file_handle);
	// Read and parse until the end of the file to find all interface names
	char iface_names_count = 0;
	char iface_names_length = 8;
	char **iface_names = malloc(sizeof(char *) * iface_names_length);
	char *iface_name = malloc(MAX_IFACE_NAME_SIZE + 1);
	while (fscanf(file_handle, " %" STR(MAX_IFACE_NAME_SIZE) "s"
				" %*llu %*llu %*llu %*llu %*llu %*llu %*llu %*llu"
				" %*llu %*llu %*llu %*llu %*llu %*llu %*llu %*llu", iface_name) > 0) {
		// Trim the trailing colon from the interface name
		iface_name[strlen(iface_name) - 1] = '\0';

		if (iface_names_count == iface_names_length) {
			iface_names = realloc(iface_names, sizeof(char *) * iface_names_length * 2);
			iface_names_length *= 2;
		}
		iface_names[iface_names_count] = iface_name;
		iface_names_count++;

		iface_name = malloc(MAX_IFACE_NAME_SIZE + 1);
	}
	fclose(file_handle);


	// Clean up existing data structures
	proc_net_dev_data *data = (proc_net_dev_data *)trace_file->data;
	cleanup_data_buffers(data);
	// Store enumerated interfaces and reallocate data structures
	data->num_ifaces = iface_names_count;
	data->iface_names = iface_names;
	data->previous_metrics = calloc(iface_names_count, sizeof(proc_net_dev_iface_metrics));
	data->current_metrics = calloc(iface_names_count, sizeof(proc_net_dev_iface_metrics));


	// Write the interface list to the output file
	write_iface_list(trace_file->output_file, sample_time, data);
}

static void parse_proc_net_dev(trace_file_t *trace_file) {
	nanosec_t sample_time = get_time();

	proc_net_dev_data *data = (proc_net_dev_data *)trace_file->data;

	FILE *file_handle = fopen(trace_file->source_file_name, "rb");
	// Read and skip the first two lines (headers)
	fgets(read_buffer, sizeof(read_buffer), file_handle);
	fgets(read_buffer, sizeof(read_buffer), file_handle);
	// Read and parse until the end of the file to find all interface statistics
	proc_net_dev_iface_metrics *next_iface = data->current_metrics;
	char iface_name[MAX_IFACE_NAME_SIZE + 1];
	int iface_id = 0;
	uint64_t recv_bytes, recv_packets, send_bytes, send_packets;
	while (fscanf(file_handle, " %" STR(MAX_IFACE_NAME_SIZE) "s"
				" %llu %llu %*llu %*llu %*llu %*llu %*llu %*llu"
				" %llu %llu %*llu %*llu %*llu %*llu %*llu %*llu", iface_name,
				&recv_bytes, &recv_packets,
				&send_bytes, &send_packets) > 0) {
		if (iface_id >= data->num_ifaces) {
			// Number of interfaces has changed, so re-enumerate all interfaces
			fclose(file_handle);
			enumerate_interfaces(trace_file);
			return;
		}

		// Trim the trailing colon from the interface name
		iface_name[strlen(iface_name) - 1] = '\0';
		// Ensure that the interface name matches the cached name
		if (strcmp(iface_name, data->iface_names[iface_id]) != 0) {
			// Interface name does not match, so re-enumerate all interfaces
			fclose(file_handle);
			enumerate_interfaces(trace_file);
			return;
		}

		// Store parsed values
		next_iface->recv_bytes = recv_bytes;
		next_iface->recv_packets = recv_packets;
		next_iface->send_bytes = send_bytes;
		next_iface->send_packets = send_packets;

		next_iface++;
		iface_id++;
	}
	if (iface_id != data->num_ifaces) {
		// Number of interfaces has changed, so re-enumerate all interfaces
		fclose(file_handle);
		enumerate_interfaces(trace_file);
		return;
	}
	fclose(file_handle);

	// Write metrics to the output file
	write_metrics(trace_file->output_file, sample_time, data);

	// Swap the metric buffers
	proc_net_dev_iface_metrics *tmp = data->previous_metrics;
	data->previous_metrics = data->current_metrics;
	data->current_metrics = tmp;
}


/**
 * Parse module initialization and cleanup
 */
static const char proc_net_dev_filename[] = "/proc/net/dev";

static void cleanup_proc_net_dev(trace_file_t *trace_file) {
	fclose(trace_file->output_file);
	cleanup_data_buffers((proc_net_dev_data *)trace_file->data);
	free(trace_file->data);
	free(trace_file);
}

trace_file_t *init_proc_net_dev_parser(const char *output_directory, const char *hostname) {
	char *output_filename = malloc(strlen(output_directory) + strlen("/proc-net-dev-") + strlen(hostname) + 1);
	*output_filename = '\0';
	strcat(output_filename, output_directory);
	strcat(output_filename, "/proc-net-dev-");
	strcat(output_filename, hostname);

	trace_file_t *trace_file = malloc(sizeof(trace_file_t));
	trace_file->parse_callback = parse_proc_net_dev;
	trace_file->cleanup_callback = cleanup_proc_net_dev;
	trace_file->source_file_name = proc_net_dev_filename;
	trace_file->data = calloc(1, sizeof(proc_net_dev_data));
	trace_file->output_file = fopen(output_filename, "wb");

	free(output_filename);

	enumerate_interfaces(trace_file);

	return trace_file;
}
