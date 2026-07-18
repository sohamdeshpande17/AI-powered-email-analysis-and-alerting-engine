package com.meridian.circular.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Parses optional {@code from}/{@code to} query strings into instants (UTC). */
final class DateParams {

    private DateParams() {
    }

    /** Start-of-day for a {@code yyyy-MM-dd} string, or a full ISO instant. */
    static Instant from(String value) {
        return parse(value, false);
    }

    /** End-of-day for a {@code yyyy-MM-dd} string, or a full ISO instant. */
    static Instant to(String value) {
        return parse(value, true);
    }

    private static Instant parse(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate d = LocalDate.parse(v);
                LocalTime t = endOfDay ? LocalTime.MAX : LocalTime.MIN;
                return d.atTime(t).toInstant(ZoneOffset.UTC);
            }
            return OffsetDateTime.parse(v).toInstant();
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid date: " + value);
        }
    }
}
