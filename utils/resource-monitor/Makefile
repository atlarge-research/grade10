
SOURCES=src/main.c src/proc_stat.c src/proc_net_dev.c src/proc_diskstats.c src/proc_meminfo.c

all: bin/resource-monitor bin/resource-monitor-dbg

bin/resource-monitor: ${SOURCES}
	gcc -std=gnu99 -O3 -o $@ ${SOURCES}

bin/resource-monitor-dbg: ${SOURCES}
	gcc -std=gnu99 -g -DDEBUG=1 -o $@ ${SOURCES}
