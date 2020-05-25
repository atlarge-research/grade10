
#ifndef __PROCFS_H__
#define __PROCFS_H__

#include "monitor.h"

/**
 * File: /proc/stat
 */
trace_file_t *init_proc_stat_parser(const char *output_directory, const char *hostname);

/**
 * File: /proc/net/dev
 */
trace_file_t *init_proc_net_dev_parser(const char *output_directory, const char *hostname);

/**
 * File: /proc/diskstats
 */
trace_file_t *init_proc_diskstats_parser(const char *output_directory, const char *hostname);

/**
 * File: /proc/meminfo
 */
trace_file_t *init_proc_meminfo_parser(const char *output_directory, const char *hostname);

#endif
