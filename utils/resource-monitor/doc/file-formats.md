# File Formats

## /proc/stat output format

Unbounded stream of `proc_stat` structures:

```c
struct proc_stat {
	u64 timestamp_ns;
	var_u32 num_cpus;
	struct {
		var_u64 user;
		var_u64 nice;
		var_u64 system;
		var_u64 idle;
		var_u64 iowait;
		var_u64 irq;
		var_u64 softirq;
		var_u64 steal;
		var_u64 guest;
		var_u64 guestnice;
	} cpu_deltas[num_cpus];
};
```

## /proc/net/dev output format

Unbounded stream of `proc_net_dev_*` structures, identifiable by a record type:

```c
enum proc_net_dev_msgtype {
	IFACE_LIST = 0,
	METRICS = 1
};

struct proc_net_dev_iface_list {
	u64 timestamp_ns;
	u8 msgtype = IFACE_LIST;
	var_u32 num_ifaces;
	string iface_names[num_ifaces]; // null-terminated ASCII
};

struct proc_net_dev_metrics {
	u64 timestamp_ns;
	u8 msgtype = METRICS;
	var_u32 num_ifaces; // equal to last proc_net_dev_iface_list.num_ifaces
	struct {
		var_u64 recv_bytes;
		var_u64 recv_packets;
		var_u64 send_bytes;
		var_u64 send_packets;
	} network_deltas[num_ifaces];	
};
```

## /proc/diskstats output format

Unbounded stream of `proc_diskstats_*` structures, identifiable by a record type:

```c
enum proc_diskstats_msgtype {
	DISK_LIST = 0,
	METRICS = 1
};

struct proc_diskstats_disk_list {
	u64 timestamp_ns;
	u8 msgtype = DISK_LIST;
	var_u32 num_disks;
	string disk_names[num_disks]; // null-terminated ASCII
};

struct proc_diskstats_metrics {
	u64 timestamp_ns;
	u8 msgtype = METRICS;
	var_u32 num_disks; // equal to last proc_diskstats_disk_list.num_disks
	struct {
		var_u64 read_completed;
		var_u64 read_sectors;
		var_u64 read_time_ms;
		var_u64 write_completed;
		var_u64 write_sectors;
		var_u64 write_time_ms;
	} disk_deltas[num_disks];
};
```
