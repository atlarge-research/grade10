
#ifndef __VARINT_H__
#define __VARINT_H__

#include <stdint.h>

static inline void write_var_uint32_t(uint32_t value, char **buffer) {
	char *next_ptr = *buffer;
	while (value >= 0x80ULL) {
		*next_ptr = 0x80 | (value & 0x7F);
		next_ptr++;
		value >>= 7;
	}

	*next_ptr = (value & 0x7F);
	next_ptr++;

	*buffer = next_ptr;
}

static inline void write_var_uint64_t(uint64_t value, char **buffer) {
	char *next_ptr = *buffer;
	while (value >= 0x80ULL) {
		*next_ptr = 0x80 | (value & 0x7F);
		next_ptr++;
		value >>= 7;
	}

	*next_ptr = (value & 0x7F);
	next_ptr++;

	*buffer = next_ptr;
}

static inline void write_var_int64_t(int64_t value, char **buffer) {
	if (value > 0)
		write_var_uint64_t((uint64_t)value << 1, buffer);
	else
		write_var_uint64_t(((uint64_t)-value) << 1 | 1, buffer);
}

#endif
