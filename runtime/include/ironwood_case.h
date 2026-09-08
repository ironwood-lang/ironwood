// SPDX-License-Identifier: MIT OR Apache-2.0
#ifndef IRONWOOD_CASE_H
#define IRONWOOD_CASE_H
#include <stdint.h>
void ironwood_case_convert(const uint16_t *text, int32_t length, _Bool upper,
        uint16_t *output, int64_t *unit_count, int64_t *byte_count);
_Bool ironwood_case_equal(const uint16_t *first, int32_t first_length,
        const uint16_t *second, int32_t second_length);
int32_t ironwood_character_is_digit(int32_t code_point);
int32_t ironwood_character_is_letter(int32_t code_point);
int32_t ironwood_character_is_upper(int32_t code_point);
int32_t ironwood_character_is_lower(int32_t code_point);
int32_t ironwood_character_to_upper(int32_t code_point);
int32_t ironwood_character_to_lower(int32_t code_point);
int32_t ironwood_character_digit_value(int32_t code_point);
int32_t ironwood_character_numeric_value(int32_t code_point);
#endif
