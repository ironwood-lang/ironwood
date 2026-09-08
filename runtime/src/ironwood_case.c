/*
 * Copyright (c) 2003, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
/*
 * Copyright (c) 1999, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 *
 * (C) Copyright Taligent, Inc. 1996, 1997 - All Rights Reserved
 * (C) Copyright IBM Corp. 1996 - 2002 - All Rights Reserved
 *
 * The original version of this source code and documentation
 * is copyrighted and owned by Taligent, Inc., a wholly-owned
 * subsidiary of IBM. These materials are provided under terms
 * of a License Agreement between Taligent and Sun. This technology
 * is protected by multiple US and International patents.
 *
 * This notice and attribution to Taligent may not be removed.
 * Taligent is a registered trademark of Taligent, Inc.
 */

/*
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 * Derived from OpenJDK: src/java.base/share/classes/java/lang/ConditionalSpecialCasing.java
 * Also derived from: src/java.base/share/classes/sun/text/RuleBasedBreakIterator.java
 * OpenJDK revision: 060c4f7589e7f13febd402f4dac3320f4c032b08
 * Translated and modified for Ironwood: 2026.
 * Ironwood extends the Classpath Exception to its modifications in this file.
 * Fixed en_US semantics; other locales and normalization machinery omitted.
 * Native UTF-16 input, compact immutable tables, word-boundary context,
 * bounded stack results, and no Java collections, iterators, or heap scratch.
 */

#include "../include/ironwood_case.h"
#include "ironwood_case_data.h"
#include <stddef.h>

static uint32_t code_point(const uint16_t *text, int32_t length, int32_t index) {
    uint32_t high = text[index];
    if (high >= 0xD800 && high <= 0xDBFF && index + 1 < length
            && text[index + 1] >= 0xDC00 && text[index + 1] <= 0xDFFF) {
        return 0x10000 + ((high - 0xD800) << 10) + text[index + 1] - 0xDC00;
    }
    return high;
}

static int32_t previous_index(const uint16_t *text, int32_t index) {
    index--;
    if (index > 0 && text[index] >= 0xDC00 && text[index] <= 0xDFFF
            && text[index - 1] >= 0xD800 && text[index - 1] <= 0xDBFF) index--;
    return index;
}

static const struct case_property *properties(uint32_t point) {
    size_t low = 0, high = sizeof(case_properties) / sizeof(case_properties[0]);
    while (low + 1 < high) {
        size_t mid = low + (high - low) / 2;
        if (case_properties[mid].start <= point) low = mid;
        else high = mid;
    }
    return &case_properties[low];
}

static const struct case_record *mapping(uint32_t point) {
    size_t low = 0, high = sizeof(case_records) / sizeof(case_records[0]);
    while (low < high) {
        size_t mid = low + (high - low) / 2;
        if (case_records[mid].point == point) return &case_records[mid];
        if (case_records[mid].point < point) low = mid + 1;
        else high = mid;
    }
    return NULL;
}

/* The fixed en_US contract needs English word boundaries for sigma. */
static int32_t next_word(const uint16_t *text, int32_t length, int32_t start) {
    if (start == length) return -1;
    int32_t result = start + (code_point(text, length, start) >= 0x10000 ? 2 : 1);
    int32_t lookahead = 0, index = start;
    int state = 1;
    const int16_t *states = word_states;
    const unsigned char *ends = word_end;
    const unsigned char *looks = word_lookahead;
    int categories = WORD_CATEGORIES;
    while (index < length && state != 0) {
        uint32_t point = code_point(text, length, index);
        if (point == 0xFFFF) break;
        const struct case_property *prop = properties(point);
        int category = prop->category;
        int32_t next = index + (point >= 0x10000 ? 2 : 1);
        if (category != -1) state = states[state * categories + category];
        if (looks[state]) {
            if (ends[state]) result = lookahead;
            else lookahead = next;
        } else if (ends[state]) result = next;
        index = next;
    }
    if (index == length && lookahead == length) result = lookahead;
    return result;
}

/* Keep the UTF-16 cursor and backward synchronization used by Java's boundary
 * queries. A scan of forward boundaries alone differs around surrogate pairs. */
static uint32_t word_previous(const uint16_t *text, int32_t length, int32_t *cursor) {
    if (*cursor == 0) return 0xFFFF;
    uint32_t low = text[--*cursor];
    if (low >= 0xDC00 && low <= 0xDFFF && *cursor > 0
            && text[*cursor - 1] >= 0xD800 && text[*cursor - 1] <= 0xDBFF) {
        uint32_t high = text[--*cursor];
        return 0x10000 + ((high - 0xD800) << 10) + low - 0xDC00;
    }
    return low;
}

static uint32_t word_next(const uint16_t *text, int32_t length, int32_t *cursor) {
    if (*cursor == length) return 0xFFFF;
    int32_t next = *cursor + (code_point(text, length, *cursor) >= 0x10000 ? 2 : 1);
    if (next >= length) return 0xFFFF;
    *cursor = next;
    return code_point(text, length, next);
}

static int32_t word_safe_start(const uint16_t *text, int32_t length, int32_t cursor) {
    int state = 1, category = 0, last_category = 0;
    uint32_t point = cursor == length ? 0xFFFF : code_point(text, length, cursor);
    while (point != 0xFFFF && state != 0) {
        last_category = category;
        category = properties(point)->category;
        if (category != -1) state = word_backwards[state * WORD_CATEGORIES + category];
        point = word_previous(text, length, &cursor);
    }
    if (point != 0xFFFF) {
        if (last_category != -1) word_next(text, length, &cursor);
        word_next(text, length, &cursor);
    }
    return cursor;
}

static _Bool word_boundary(const uint16_t *text, int32_t length, int32_t offset, int32_t *cached) {
    if (offset == 0) return 1;
    int32_t preceding = offset - 1;
    int32_t result;
    if (preceding == 0) {
        result = next_word(text, length, 0);
    } else {
        result = *cached;
        if (result >= preceding || result <= -1) result = word_safe_start(text, length, preceding);
        while (result != -1 && result <= preceding) result = next_word(text, length, result);
    }
    *cached = result;
    return result == offset;
}

static _Bool final_cased(const uint16_t *text, int32_t length, int32_t index) {
    int32_t cached = -1;
    for (int32_t i = index; i >= 0 && !word_boundary(text, length, i, &cached);) {
        i = previous_index(text, i);
        if (properties(code_point(text, length, i))->cased) {
            for (i = index + 1; i < length && !word_boundary(text, length, i, &cached);) {
                uint32_t point = code_point(text, length, i);
                if (properties(point)->cased) return 0;
                i += point >= 0x10000 ? 2 : 1;
            }
            return 1;
        }
    }
    return 0;
}

void ironwood_case_convert(const uint16_t *text, int32_t length, _Bool upper,
        uint16_t *output, int64_t *unit_count, int64_t *byte_count) {
    int64_t count = 0, bytes = 0;
    uint16_t previous = 0;
    for (int32_t index = 0; index < length;) {
        uint32_t point = code_point(text, length, index);
        uint16_t special[3];
        int n = -1;
        if (point < 128) {
            special[0] = (uint16_t) point;
            if (upper && point >= 'a' && point <= 'z') special[0] -= 32;
            if (!upper && point >= 'A' && point <= 'Z') special[0] += 32;
            n = 1;
        }
        if (!upper && point == 0x3A3 && final_cased(text, length, index)) {
            special[0] = 0x3C2;
            n = 1;
        }
        const uint16_t *units = special;
        if (n < 0) {
            const struct case_record *entry = mapping(point);
            if (entry != NULL) {
                units = upper ? entry->upper : entry->lower;
                n = upper ? entry->upper_length : entry->lower_length;
            } else {
                units = text + index;
                n = point >= 0x10000 ? 2 : 1;
            }
        }
        for (int j = 0; j < n; j++) {
            uint16_t unit = units[j];
            if (output != NULL) output[count] = unit;
            count++;
            bytes += unit < 128 ? 1 : unit < 2048 ? 2 : 3;
            if (unit >= 0xDC00 && unit <= 0xDFFF && previous >= 0xD800 && previous <= 0xDBFF) bytes -= 2;
            previous = unit;
        }
        index += point >= 0x10000 ? 2 : 1;
    }
    *unit_count = count;
    *byte_count = bytes;
}

static uint32_t folded(uint32_t point) {
    if (point < 128) return point >= 'A' && point <= 'Z' ? point + 32 : point;
    const struct case_record *entry = mapping(point);
    uint32_t upper = entry == NULL ? point : entry->simple_upper;
    entry = mapping(upper);
    return entry == NULL ? upper : entry->simple_lower;
}

_Bool ironwood_case_equal(const uint16_t *first, int32_t first_length,
        const uint16_t *second, int32_t second_length) {
    if (first_length != second_length) return 0;
    int32_t a = 0, b = 0;
    while (a < first_length && b < second_length) {
        uint32_t left = code_point(first, first_length, a);
        uint32_t right = code_point(second, second_length, b);
        if (left != right && folded(left) != folded(right)) return 0;
        a += left >= 0x10000 ? 2 : 1;
        b += right >= 0x10000 ? 2 : 1;
    }
    return a == first_length && b == second_length;
}

static _Bool in_character_ranges(uint32_t point,
        const struct character_range *ranges, size_t count) {
    size_t low = 0, high = count;
    while (low < high) {
        size_t mid = low + (high - low) / 2;
        if (point < ranges[mid].start) high = mid;
        else if (point > ranges[mid].end) low = mid + 1;
        else return 1;
    }
    return 0;
}

static int32_t character_value(uint32_t point,
        const struct character_value *values, size_t count) {
    size_t low = 0, high = count;
    while (low < high) {
        size_t mid = low + (high - low) / 2;
        if (point < values[mid].point) high = mid;
        else if (point > values[mid].point) low = mid + 1;
        else return values[mid].value;
    }
    return -1;
}

int32_t ironwood_character_is_digit(int32_t code_point_value) {
    uint32_t point = (uint32_t) code_point_value;
    return in_character_ranges(point, character_digit_ranges,
            sizeof(character_digit_ranges) / sizeof(character_digit_ranges[0]));
}

int32_t ironwood_character_is_letter(int32_t code_point_value) {
    uint32_t point = (uint32_t) code_point_value;
    return in_character_ranges(point, character_letter_ranges,
            sizeof(character_letter_ranges) / sizeof(character_letter_ranges[0]));
}

int32_t ironwood_character_is_upper(int32_t code_point_value) {
    uint32_t point = (uint32_t) code_point_value;
    return in_character_ranges(point, character_upper_ranges,
            sizeof(character_upper_ranges) / sizeof(character_upper_ranges[0]));
}

int32_t ironwood_character_is_lower(int32_t code_point_value) {
    uint32_t point = (uint32_t) code_point_value;
    return in_character_ranges(point, character_lower_ranges,
            sizeof(character_lower_ranges) / sizeof(character_lower_ranges[0]));
}

int32_t ironwood_character_to_upper(int32_t code_point_value) {
    const struct case_record *entry = mapping((uint32_t) code_point_value);
    return entry == NULL ? code_point_value : (int32_t) entry->simple_upper;
}

int32_t ironwood_character_to_lower(int32_t code_point_value) {
    const struct case_record *entry = mapping((uint32_t) code_point_value);
    return entry == NULL ? code_point_value : (int32_t) entry->simple_lower;
}

int32_t ironwood_character_digit_value(int32_t code_point_value) {
    return character_value((uint32_t) code_point_value, character_digit_values,
            sizeof(character_digit_values) / sizeof(character_digit_values[0]));
}

int32_t ironwood_character_numeric_value(int32_t code_point_value) {
    return character_value((uint32_t) code_point_value, character_numeric_values,
            sizeof(character_numeric_values) / sizeof(character_numeric_values[0]));
}
