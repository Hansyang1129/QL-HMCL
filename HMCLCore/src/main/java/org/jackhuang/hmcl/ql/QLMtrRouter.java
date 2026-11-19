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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/// Local path planning over the Qinglan MTR network.
///
/// The whole network graph comes from the {@code stations-and-routes} fetch, so
/// path queries run entirely offline against cached data and never hit the map
/// API. Alternatives are produced by iteratively banning one ride edge of the
/// previous solution and rerunning Dijkstra, which is enough variety for a
/// launcher-sized UI without a full Yen k-shortest loop.
@NotNullByDefault
public final class QLMtrRouter {

    /// Transfer walk time in milliseconds across a {@code connections} link.
    private static final long WALK_TIME = 120_000;

    /// Extra cost in milliseconds when consecutive ride edges use different routes.
    private static final long TRANSFER_PENALTY = 180_000;

    private QLMtrRouter() {
    }

    /// One leg of an itinerary.
    ///
    /// A ride leg is one merged run on a single route; walking legs describe
    /// transfers across station-level {@code connections} links and carry no
    /// route information.
    public record MtrLeg(boolean walk, @Nullable String routeId, @Nullable String routeName,
                         @Nullable String routeNumber, int routeColor,
                         String fromStationId, String fromStationName,
                         String toStationId, String toStationName,
                         long time, int stops) {
    }

    /// One candidate itinerary from the origin to the destination platform.
    ///
    /// @param legs      ordered ride and walk legs
    /// @param total     total travel time in milliseconds, including penalties and walks
    /// @param transfers number of line changes between ride legs
    /// @param signature deduplication key over the line-and-segment sequence;
    ///                  parallel variants of the same line that serve the same
    ///                  segments collapse to one signature
    /// @param rideEdges every single-hop ride edge used, as {@code route|from>to};
    ///                  these are what the alternative search bans
    public record MtrPath(@Unmodifiable List<MtrLeg> legs, long total, int transfers, String signature,
                              @Unmodifiable List<String> rideEdges) {
    }

    /// Finds up to {@code maxPaths} itineraries between two platform-owned stations.
    ///
    /// @param sar        network snapshot to plan on; must contain both stations
    /// @param fromStationId origin station id
    /// @param toStationId   destination station id
    /// @param maxPaths   maximum number of distinct itineraries; may deliver fewer
    public static @Unmodifiable List<MtrPath> findPaths(QLMtr.SarData sar, String fromStationId,
                                                        String toStationId, int maxPaths) {
        Model model = new Model(sar);
        List<MtrPath> results = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();
        Set<String> banned = new HashSet<>();

        for (int attempt = 0; attempt < maxPaths * 3 && results.size() < maxPaths; attempt++) {
            MtrPath candidate = dijkstra(model, fromStationId, toStationId, banned);
            if (candidate == null)
                break;
            if (signatures.add(candidate.signature()))
                results.add(candidate);
            if (candidate.legs().isEmpty())
                break;
            if (candidate.rideEdges().isEmpty())
                break; // pure walking solutions have no alternative shape
            // Ban every single-hop ride edge the solution used. The merged legs of a
            // plan span several hops, so banning a leg's endpoints would never match
            // an edge and the next run would return the very same path.
            banned.addAll(candidate.rideEdges());
        }
        return List.copyOf(results);
    }

    /// Directional ride or walk edge between two platform-owned stations.
    private static final class Edge {
        final String routeId; // null marks a transfer walk
        final String fromStation;
        final String toStation;
        final long cost;

        Edge(@Nullable String routeId, String fromStation, String toStation, long cost) {
            this.routeId = routeId;
            this.fromStation = fromStation;
            this.toStation = toStation;
            this.cost = cost;
        }

        boolean ride() {
            return routeId != null;
        }
    }

    /// Immutable graph plus lookup maps for a search run.
    private static final class Model {
        private final Map<String, List<Edge>> adjacency = new HashMap<>();
        private final Map<String, QLMtr.MtrStation> stations;
        private final Map<String, QLMtr.MtrRoute> routes;

        Model(QLMtr.SarData sar) {
            this.stations = new HashMap<>();
            for (QLMtr.MtrStation station : sar.stations()) {
                stations.put(station.id(), station);
                adjacency.put(station.id(), new ArrayList<>());
            }
            this.routes = new HashMap<>();
            for (QLMtr.MtrRoute route : sar.routes())
                routes.put(route.id(), route);

            for (QLMtr.MtrRoute route : sar.routes()) {
                if (route.hidden() || route.stops().size() < 2)
                    continue;
                int stopCount = route.stops().size();
                for (int i = 0; i + 1 < stopCount; i++) {
                    String a = route.stops().get(i).stationId();
                    String b = route.stops().get(i + 1).stationId();
                    if (!stations.containsKey(a) || !stations.containsKey(b))
                        continue;
                    long cost = i < route.durations().size() ? route.durations().get(i) : 60_000;
                    adjacency.get(a).add(new Edge(route.id(), a, b, cost));
                    adjacency.get(b).add(new Edge(route.id(), b, a, cost));
                }
                if (stopCount > 2 && isCircular(route)) {
                    String a = route.stops().get(stopCount - 1).stationId();
                    String b = route.stops().get(0).stationId();
                    if (stations.containsKey(a) && stations.containsKey(b)) {
                        long avg = route.durations().isEmpty() ? 60_000
                                : route.durations().stream().mapToLong(Long::longValue).sum()
                                        / route.durations().size();
                        adjacency.get(a).add(new Edge(route.id(), a, b, avg));
                        adjacency.get(b).add(new Edge(route.id(), b, a, avg));
                    }
                }
            }

            for (QLMtr.MtrStation station : sar.stations()) {
                List<Edge> edges = adjacency.get(station.id());
                for (String connection : station.connections()) {
                    if (connection.equals(station.id()) || !stations.containsKey(connection))
                        continue;
                    edges.add(new Edge(null, station.id(), connection, WALK_TIME));
                }
            }
        }

        private static boolean isCircular(QLMtr.MtrRoute route) {
            return !"NONE".equals(route.circularState()) && !route.circularState().isEmpty();
        }

        QLMtr.MtrStation station(String id) {
            return stations.get(id);
        }
    }

    /// Search state: current node plus the route the search arrived on.
    ///
    /// The route memory is what makes changeover costs possible: switching to a
    /// different ride edge adds {@link #TRANSFER_PENALTY}, staying on the same
    /// route does not, and walks pass the last route through unchanged.
    private record State(String station, @Nullable String lastRoute, long cost) {

        String key() {
            return station + "|" + (lastRoute == null ? "" : lastRoute);
        }
    }

    /// Back-pointer of a settled state.
    private record Trace(@Nullable State previous, @Nullable Edge edge, long cost) {
    }

    private static @Nullable MtrPath dijkstra(Model model, String fromId, String toId, Set<String> banned) {
        if (!model.adjacency.containsKey(fromId) || !model.adjacency.containsKey(toId))
            return null;

        PriorityQueue<State> queue = new PriorityQueue<>((a, b) -> Long.compare(a.cost(), b.cost()));
        Map<String, Trace> traces = new HashMap<>();
        State start = new State(fromId, null, 0);
        traces.put(start.key(), new Trace(null, null, 0));
        queue.add(start);

        while (!queue.isEmpty()) {
            State state = queue.poll();
            Trace settled = traces.get(state.key());
            if (settled == null || state.cost() > settled.cost())
                continue;
            if (state.station().equals(toId))
                return tracePath(model, traces, state);

            for (Edge edge : model.adjacency.get(state.station())) {
                // banned marks only ever block ride edges
                if (edge.ride() && banned.contains(edge.routeId + "|" + edge.fromStation + ">" + edge.toStation))
                    continue;

                long cost = state.cost() + edge.cost;
                if (edge.ride() && state.lastRoute() != null && !state.lastRoute().equals(edge.routeId))
                    cost += TRANSFER_PENALTY;

                State next = new State(edge.toStation, edge.ride() ? edge.routeId : state.lastRoute(), cost);
                String key = next.key();
                Trace known = traces.get(key);
                if (known == null || cost < known.cost()) {
                    traces.put(key, new Trace(state, edge, cost));
                    queue.add(new State(next.station(), next.lastRoute(), cost));
                }
            }
        }
        return null;
    }

    /// Follows the settled back-pointers from the arrival state to the origin
    /// and merges edges into ride and walk legs.
    ///
    /// Two plans count as the same one when they ride the same lines over the
    /// same segments, which lets parallel route variants of one line dedup into
    /// the cheapest candidate. Transfer walks do not count as line changes: a
    /// swap between two different routes does.
    private static @Nullable MtrPath tracePath(Model model, Map<String, Trace> traces, State arrival) {
        List<Edge> path = new ArrayList<>();
        State cursor = arrival;
        while (true) {
            Trace trace = traces.get(cursor.key());
            if (trace == null)
                return null;
            if (trace.edge() == null)
                break;
            path.add(trace.edge());
            if (trace.previous() == null)
                break;
            cursor = trace.previous();
        }
        // Java 17 target: List.reversed() only exists from 21 on.
        List<Edge> ordered = new ArrayList<>(path);
        Collections.reverse(ordered);

        List<MtrLeg> legs = new ArrayList<>();
        List<String> rideEdges = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        long total = 0;
        int transfers = 0;
        // Route of the last boarding leg; a change adds the same penalty the search
        // charged, so the reported total matches what made this plan the winner.
        @Nullable String lastRideRoute = null;

        int index = 0;
        while (index < ordered.size()) {
            Edge edge = ordered.get(index);
            if (!edge.ride()) {
                QLMtr.MtrStation from = model.station(edge.fromStation);
                QLMtr.MtrStation to = model.station(edge.toStation);
                legs.add(new MtrLeg(true, null, null, null, 0,
                        from.id(), QLMtr.primaryName(from.name()),
                        to.id(), QLMtr.primaryName(to.name()), edge.cost, 0));
                total += edge.cost;
                index++;
                continue;
            }
            // merge consecutive ride edges of the same route into one leg
            QLMtr.MtrRoute route = model.routes.get(edge.routeId);
            String routeName = route == null ? edge.routeId : QLMtr.primaryName(route.name());
            String routeNumber = route == null ? "" : route.number();
            int routeColor = route == null ? 0 : route.color();
            String fromId = edge.fromStation;
            long legTime = edge.cost;
            int stops = 1;
            rideEdges.add(edgeKey(edge));
            index++;
            while (index < ordered.size()) {
                Edge follow = ordered.get(index);
                if (follow.ride() && follow.routeId.equals(edge.routeId)) {
                    legTime += follow.cost;
                    stops++;
                    rideEdges.add(edgeKey(follow));
                    index++;
                } else
                    break;
            }
            String toId = ordered.get(index - 1).toStation;
            QLMtr.MtrStation from = model.station(fromId);
            QLMtr.MtrStation to = model.station(toId);
            legs.add(new MtrLeg(false, edge.routeId, routeName, routeNumber, routeColor,
                    from.id(), QLMtr.primaryName(from.name()),
                    to.id(), QLMtr.primaryName(to.name()), legTime, stops));
            // The signature carries the display identity of the leg, not the raw route
            // id: parallel variants of one line ride identical name-and-segment rows.
            signature.append(routeName).append("#").append(routeNumber)
                    .append("|").append(fromId).append(">").append(toId).append('>');
            if (lastRideRoute != null && !lastRideRoute.equals(edge.routeId)) {
                transfers++;
                total += TRANSFER_PENALTY;
            }
            lastRideRoute = edge.routeId;
            total += legTime;
        }
        if (legs.isEmpty())
            return null;
        return new MtrPath(List.copyOf(legs), total, transfers, signature.toString(), List.copyOf(rideEdges));
    }

    /// Key of one single-hop ride edge, in the form used by the ban set.
    private static String edgeKey(Edge edge) {
        return edge.routeId + "|" + edge.fromStation + ">" + edge.toStation;
    }

    /// Shapes the web's single directions reply into the same card model as the local search.
    ///
    /// The reply speaks in ids and epoch times only; names and colors are resolved
    /// against the network snapshot. Walk legs that start or end outside the
    /// network fall back to the endpoint display names the plan picked. Two
    /// cleanups follow: walks inside one station (same platform grouping, only a
    /// platform change) carry no visible information and are dropped, and a
    /// through-service the finder split into per-segment connections on the same
    /// route is merged back into one ride.
    ///
    /// @return a path over the cleaned reply legs, or {@code null} when the reply is empty
    public static @Nullable MtrPath directionsToPath(QLMtr.SarData sar, QLMtr.MtrDirections directions,
                                                     String fromName, String toName) {
        if (directions.legs().isEmpty())
            return null;

        Map<String, QLMtr.MtrStation> stations = new HashMap<>();
        for (QLMtr.MtrStation station : sar.stations())
            stations.putIfAbsent(station.id(), station);
        Map<String, QLMtr.MtrRoute> routes = new HashMap<>();
        for (QLMtr.MtrRoute route : sar.routes())
            routes.putIfAbsent(route.id(), route);

        List<MtrLeg> legs = new ArrayList<>();
        for (QLMtr.MtrDirectionsLeg leg : directions.legs()) {
            long time = Math.max(0, leg.arrival() - leg.departure());
            if (!leg.ride()) {
                legs.add(new MtrLeg(true, null, null, null, 0,
                        leg.startStationId(), stationName(stations, leg.startStationId(), fromName),
                        leg.endStationId(), stationName(stations, leg.endStationId(), toName),
                        time, 0));
                continue;
            }

            QLMtr.MtrRoute route = routes.get(leg.routeId());
            String routeName = route == null ? leg.routeId() : QLMtr.primaryName(route.name());
            String routeNumber = route == null ? "" : route.number();
            int routeColor = route == null ? 0 : route.color();
            legs.add(new MtrLeg(false, leg.routeId(), routeName, routeNumber, routeColor,
                    leg.startStationId(), stationName(stations, leg.startStationId(), fromName),
                    leg.endStationId(), stationName(stations, leg.endStationId(), toName),
                    time, 0));
        }
        if (legs.isEmpty())
            return null;

        int transfers = 0;
        @Nullable String lastRideRoute = null;
        List<MtrLeg> rendered = new ArrayList<>();
        for (MtrLeg leg : legs) {
            if (leg.walk() && leg.fromStationName().equals(leg.toStationName()))
                continue;
            if (!leg.walk() && !rendered.isEmpty()) {
                MtrLeg previous = rendered.get(rendered.size() - 1);
                if (!previous.walk() && previous.routeId().equals(leg.routeId())) {
                    // same route continues after a dropped same-station walk or a split connection
                    rendered.set(rendered.size() - 1, new MtrLeg(false, previous.routeId(),
                            previous.routeName(), previous.routeNumber(), previous.routeColor(),
                            previous.fromStationId(), previous.fromStationName(),
                            leg.toStationId(), leg.toStationName(),
                            previous.time() + leg.time(), previous.stops() + leg.stops()));
                    continue;
                }
            }
            if (!leg.walk() && lastRideRoute != null && !lastRideRoute.equals(leg.routeId()))
                transfers++;
            if (!leg.walk())
                lastRideRoute = leg.routeId();
            rendered.add(leg);
        }
        if (rendered.isEmpty())
            return null;

        long total = Math.max(0,
                directions.legs().get(directions.legs().size() - 1).arrival()
                        - directions.legs().get(0).departure());
        return new MtrPath(List.copyOf(rendered), total, transfers, "", List.of());
    }

    /// The primary station name of an id, or the given display fallback when the id is blank or unknown.
    private static String stationName(Map<String, QLMtr.MtrStation> stations, String stationId, String fallback) {
        if (stationId.isEmpty())
            return fallback;
        QLMtr.MtrStation station = stations.get(stationId);
        return station == null ? fallback : QLMtr.primaryName(station.name());
    }
}
