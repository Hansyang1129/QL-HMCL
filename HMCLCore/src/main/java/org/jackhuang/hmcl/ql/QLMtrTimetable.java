/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ql;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/// Expands MTR departure packs into concrete departure times.
///
/// {@code departures} responses carry per-route groups whose values are
/// offsets relative to the response time, so every computation needs the
/// capture time of the enclosing {@link QLMtr.DeparturesData}.
@NotNullByDefault
public final class QLMtrTimetable {

    /// Timetable entries older than this are not interesting anymore.
    private static final long MIN_PAST_MS = 60_000;

    private QLMtrTimetable() {
    }

    /// One concrete departure of a route at one stop.
    ///
    /// @param departureAt epoch milliseconds when the train leaves the first stop
    /// @param arrivalAt   epoch milliseconds when the train arrives at the queried stop
    /// @param deviation   deviation of the enclosing group in milliseconds
    public record TimedDeparture(long departureAt, long arrivalAt, long deviation) {
    }

    /// Lists upcoming departures of {@code route} that reach stop number
    /// {@code stopIndex} (0-based), ascending by departure time.
    ///
    /// @param departureData departure packs as fetched from the map API
    /// @param route    the route whose pack is consulted
    /// @param stopIndex target stop index inside the route
    /// @param now      current epoch milliseconds
    /// @param limit    maximum number of results
    public static @Unmodifiable List<TimedDeparture> nextAt(QLMtr.DeparturesData departureData,
                                                            QLMtr.MtrRoute route, int stopIndex,
                                                            long now, int limit) {
        List<QLMtr.DepartureGroup> groups = departureData.byRoute().get(route.id());
        if (groups == null)
            return List.of();

        long offsetToStop = 0;
        for (int i = 0; i < stopIndex && i < route.durations().size(); i++)
            offsetToStop += route.durations().get(i);

        List<TimedDeparture> departures = new ArrayList<>();
        for (QLMtr.DepartureGroup group : groups) {
            for (long offset : group.offsets()) {
                long departureAt = departureData.time() + offset;
                if (departureAt < now - MIN_PAST_MS)
                    continue;
                departures.add(new TimedDeparture(departureAt, departureAt + offsetToStop, group.deviation()));
            }
        }
        // records are not Comparable: natural ordering would throw ClassCastException
        departures.sort(java.util.Comparator.comparingLong(TimedDeparture::departureAt)
                .thenComparingLong(TimedDeparture::arrivalAt));
        if (departures.size() > limit)
            return List.copyOf(departures.subList(0, limit));
        return List.copyOf(departures);
    }
}
