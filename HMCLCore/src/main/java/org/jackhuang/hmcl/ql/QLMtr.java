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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Client for the Qinglan MTR transit network on the game server.
///
/// Data comes from the MTR mod's HTTP map API whose base is configured in
/// {@code ql-endpoints.json} ({@code mtrBase}); nothing is hardcoded here.
/// Three endpoints are used, all unauthenticated: {@code stations-and-routes}
/// carries the full static network, {@code departures} carries per-route
/// timetable packs whose values are offsets relative to the response time, and
/// {@code arrivals} (POST) answers real-time next trains for a batch of
/// platform-owned station ids. Everything runs over plain HTTP and each request
/// is retried a few times before failing.
@NotNullByDefault
public final class QLMtr {

    /// @return the configured MTR map API base, or an error when this shell has no endpoint
    /// @throws IOException when {@code ql-endpoints.json} carries no {@code mtrBase}
    public static String base() throws IOException {
        String configured = QLEndpoints.mtrBase();
        if (configured == null)
            throw new IOException("MTR 地图地址未配置 (ql-endpoints.json: mtrBase)");
        return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
    }

    private static final String API_PATH = "/mtr/api/map/";
    private static final String DIMENSION = "minecraft:overworld";
    private static final int RETRIES = 3;
    private static final long ARRIVALS_FRESH_MS = 4000;

    private QLMtr() {
    }

    /// A station of the network as reported by {@code stations-and-routes}.
    ///
    /// <p>In the MTR mod every platform owns its own id; grouped named stations
    /// share those ids through {@link #connections()}. Names are bilingual
    /// {@code "Chinese|English"}; use {@link #primaryName(String)} for display.
    public record MtrStation(String id, String name, int color, @Unmodifiable List<String> connections) {
    }

    /// One stop of a route.
    ///
    /// @param stationId id of the platform-owned station
    /// @param platformName label of the platform, e.g. {@code "2"}
    /// @param dwellTime dwell time of trains at this stop in milliseconds
    public record MtrRouteStop(String stationId, String platformName, long dwellTime) {
    }

    /// A route (line) of the network.
    ///
    /// @param id          stable route identifier as an unsigned 16-digit hex string
    /// @param name        bilingual name; {@code "Name||Direction"} suffix marks the direction
    /// @param color       sRGB display color
    /// @param number      short service number, may be empty
    /// @param hidden      whether the route is hidden from the public line map
    /// @param circularState {@code NONE} for normal routes; circular otherwise
    /// @param stops       ordered stops of the route
    /// @param durations   run time in milliseconds from stop i to stop i+1; one entry fewer than stops
    public record MtrRoute(String id, String name, int color, String number, boolean hidden,
                           String circularState, @Unmodifiable List<MtrRouteStop> stops,
                           @Unmodifiable List<Long> durations) {
    }

    /// Snapshot of the whole network from one {@code stations-and-routes} fetch.
    ///
    /// @param time   server wall clock in epoch milliseconds when captured
    /// @param stations all stations of the network
    /// @param routes   all routes, including hidden ones
    public record SarData(long time, @Unmodifiable List<MtrStation> stations,
                          @Unmodifiable List<MtrRoute> routes) {
    }

    /// One segment of the single path the server's directions finder returned.
    ///
    /// A leg is either a ride on a route or a walk between platforms. The reply
    /// carries no line display fields, only ids and platform labels, so callers
    /// resolve names and colors from the network snapshot; every leg carries
    /// epoch times, which keeps rendering as simple as the local plan cards.
    ///
    /// @param ride        whether this leg is a train ride; walks have a blank route id
    /// @param routeId     route id as an unsigned 16-digit hex string, blank for walks
    /// @param startStationId platform-owned station id where the leg begins, blank for entry walks
    /// @param endStationId   platform-owned station id where the leg ends, blank for the exit walk
    /// @param startPlatformName label of the boarding platform, may be blank for walks
    /// @param endPlatformName   label of the alighting platform, may be blank for walks
    /// @param departure   when the leg starts, in epoch milliseconds
    /// @param arrival     when the leg ends, in epoch milliseconds
    /// @param walkingDistance walking meters, {@code 0} for rides
    public record MtrDirectionsLeg(boolean ride, String routeId, String startStationId, String endStationId,
                                   String startPlatformName, String endPlatformName,
                                   long departure, long arrival, long walkingDistance) {
    }

    /// The single path of one {@code directions} query.
    ///
    /// @param time server wall clock in epoch milliseconds when answered
    /// @param legs ordered ride and walk legs between the request's two stations
    public record MtrDirections(long time, @Unmodifiable List<MtrDirectionsLeg> legs) {
    }

    /// One timetable group of a route.
    ///
    /// A group is a cycle of departures sharing the same deviation. Offsets are
    /// relative to the capture time of the enclosing {@link DeparturesData}.
    ///
    /// @param deviation deviation of the group in milliseconds
    /// @param offsets   departure offsets in milliseconds, ascending, may be negative
    public record DepartureGroup(long deviation, @Unmodifiable List<Long> offsets) {
    }

    /// Departure packs of several routes from one {@code departures} fetch.
    ///
    /// @param time   server wall clock in epoch milliseconds; origin of all offsets
    /// @param byRoute route id mapped to its timetable groups; only routes with a timetable appear
    public record DeparturesData(long time, @Unmodifiable Map<String, List<DepartureGroup>> byRoute) {
    }

    /// One real-time arrival predicted for a platform.
    ///
    /// @param destination  bilingual destination shown on the train front
    /// @param arrival      predicted arrival time in epoch milliseconds
    /// @param departure    predicted departure time in epoch milliseconds
    /// @param deviation    deviation of this particular train in milliseconds
    /// @param realtime     whether this is a real dispatched train rather than a timetable entry
    /// @param departureIndex index of the departure inside its timetable group
    /// @param terminating  whether this train terminates here
    /// @param routeId      route id as an unsigned 16-digit hex string
    /// @param routeName    bilingual route name
    /// @param routeNumber  short service number, may be empty
    /// @param routeColor   sRGB display color of the route
    /// @param platformId   platform-owned station id as an unsigned 16-digit hex string
    /// @param platformName label of the platform
    public record MtrArrival(String destination, long arrival, long departure, long deviation,
                             boolean realtime, int departureIndex, boolean terminating,
                             String routeId, String routeName, String routeNumber, int routeColor,
                             String platformId, String platformName, @Unmodifiable List<String> cars) {
    }

    private static volatile @Nullable SarData sarCache;
    private static volatile @Nullable DeparturesData departuresCache;
    private static final Map<String, CachedArrivals> arrivalsCache = new HashMap<>();

    private record CachedArrivals(long fetchedAt, @Unmodifiable List<MtrArrival> rows) {
    }

    /// Fetches (and caches) the full network snapshot.
    ///
    /// Callers that tolerate staleness may prefer {@link #sarIfPresent()} to
    /// keep the UI responsive offline.
    public static SarData fetchSar() throws IOException {
        return fetchSar(base());
    }

    /// Same as {@link #fetchSar()} but against an explicit map API base URL.
    ///
    /// <p>Package-private seam so the tests can point the parser at a local fake
    /// server; production callers use the no-argument form.</p>
    static SarData fetchSar(String base) throws IOException {
        String body = doGetRetry(base, sarEndpoint("stations-and-routes"));
        JsonObject root = parse(body, "network snapshot");
        JsonObject data = root.getAsJsonObject("data");

        List<MtrStation> stations = new ArrayList<>();
        for (JsonElement element : arrayOrEmpty(data, "stations")) {
            JsonObject item = element.getAsJsonObject();
            String id = trimToHex(JsonUtils.getString(item, "id"));
            if (id == null)
                continue;
            List<String> connections = new ArrayList<>();
            JsonArray connectionArray = arrayOrEmpty(item, "connections");
            for (JsonElement connection : connectionArray) {
                String connectionId = trimToHex(connection.getAsString());
                if (connectionId != null)
                    connections.add(connectionId);
            }
            stations.add(new MtrStation(
                    id,
                    JsonUtils.getString(item, "name", ""),
                    JsonUtils.getInt(item, "color", 0),
                    List.copyOf(connections)));
        }

        List<MtrRoute> routes = new ArrayList<>();
        for (JsonElement element : arrayOrEmpty(data, "routes")) {
            JsonObject item = element.getAsJsonObject();
            String id = trimToHex(JsonUtils.getString(item, "id"));
            if (id == null)
                continue;
            List<MtrRouteStop> stops = new ArrayList<>();
            for (JsonElement stopElement : arrayOrEmpty(item, "stations")) {
                JsonObject stop = stopElement.getAsJsonObject();
                String stopId = trimToHex(JsonUtils.getString(stop, "id"));
                if (stopId == null)
                    continue;
                stops.add(new MtrRouteStop(
                        stopId,
                        JsonUtils.getString(stop, "name", ""),
                        stop.get("dwellTime") == null ? 0 : stop.get("dwellTime").getAsLong()));
            }
            List<Long> durations = new ArrayList<>();
            JsonArray durationArray = arrayOrEmpty(item, "durations");
            for (JsonElement duration : durationArray)
                durations.add(duration.getAsLong());
            routes.add(new MtrRoute(
                    id,
                    JsonUtils.getString(item, "name", ""),
                    JsonUtils.getInt(item, "color", 0),
                    JsonUtils.getString(item, "number", ""),
                    JsonUtils.getBoolean(item, "hidden", false),
                    JsonUtils.getString(item, "circularState", "NONE"),
                    List.copyOf(stops),
                    List.copyOf(durations)));
        }

        SarData sar = new SarData(root.get("currentTime") == null ? System.currentTimeMillis()
                : root.get("currentTime").getAsLong(), List.copyOf(stations), List.copyOf(routes));
        sarCache = sar;
        return sar;
    }

    /// Collects the platform-owned ids that make up one physical station.
    ///
    /// <p>The map API models every platform as its own station id and links the
    /// platforms of one station through {@code connections}, so the closure over
    /// those links is the correct grouping for station-wide views. A station that
    /// carries no link at all falls back to grouping by the Chinese part of the
    /// bilingual name, which keeps older exports usable.</p>
    ///
    /// @param sar       the network snapshot to walk
    /// @param stationId the platform-owned id the user picked
    /// @return every id belonging to the same physical station, always including
    ///         {@code stationId} itself
    public static @Unmodifiable Set<String> stationGroupIds(SarData sar, String stationId) {
        Map<String, MtrStation> byId = new HashMap<>();
        for (MtrStation station : sar.stations())
            byId.putIfAbsent(station.id(), station);

        Set<String> group = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        group.add(stationId);
        pending.add(stationId);
        while (!pending.isEmpty()) {
            MtrStation node = byId.get(pending.poll());
            if (node == null)
                continue;
            for (String connection : node.connections())
                if (group.add(connection))
                    pending.add(connection);
        }
        if (group.size() > 1)
            return Set.copyOf(group);

        // No links reported: group by name instead of leaving the caller with one platform.
        MtrStation self = byId.get(stationId);
        if (self == null)
            return Set.of(stationId);
        String name = primaryName(self.name());
        Set<String> byName = new HashSet<>();
        for (MtrStation station : sar.stations())
            if (primaryName(station.name()).equals(name))
                byName.add(station.id());
        byName.add(stationId);
        return Set.copyOf(byName);
    }

    /// Returns the last network snapshot, or {@code null} when none was fetched yet.
    public static @Nullable SarData sarIfPresent() {
        return sarCache;
    }

    /// Fetches (and caches) timetable packs for all routes that have one.
    ///
    /// Roughly 0.7&nbsp;MB of JSON; polling at multi-second intervals is fine.
    public static DeparturesData fetchDepartures() throws IOException {
        return fetchDepartures(base());
    }

    /// Same as {@link #fetchDepartures()} against an explicit map API base URL
    /// (test seam, see {@link #fetchSar(String)}).
    static DeparturesData fetchDepartures(String base) throws IOException {
        String body = doGetRetry(base, sarEndpoint("departures"));
        JsonObject root = parse(body, "departures");
        JsonObject data = root.getAsJsonObject("data");

        long time = root.get("currentTime") == null ? System.currentTimeMillis()
                : root.get("currentTime").getAsLong();
        Map<String, List<DepartureGroup>> byRoute = new LinkedHashMap<>();
        for (JsonElement element : arrayOrEmpty(data, "departures")) {
            JsonObject item = element.getAsJsonObject();
            String routeId = trimToHex(item.has("id") && !item.get("id").isJsonNull()
                    ? item.get("id").getAsString() : null);
            if (routeId == null)
                continue;
            List<DepartureGroup> groups = new ArrayList<>();
            for (JsonElement groupElement : arrayOrEmpty(item, "departures")) {
                JsonObject group = groupElement.getAsJsonObject();
                List<Long> offsets = new ArrayList<>();
                for (JsonElement offset : arrayOrEmpty(group, "departures"))
                    offsets.add(offset.getAsLong());
                groups.add(new DepartureGroup(
                        group.get("deviation") == null ? 0 : group.get("deviation").getAsLong(),
                        List.copyOf(offsets)));
            }
            if (!groups.isEmpty())
                byRoute.put(routeId, List.copyOf(groups));
        }

        DeparturesData departures = new DeparturesData(time, Map.copyOf(byRoute));
        departuresCache = departures;
        return departures;
    }

    /// Returns the last departure packs, or {@code null} when none was fetched yet.
    public static @Nullable DeparturesData departuresIfPresent() {
        return departuresCache;
    }

    /// Fetches real-time arrivals for the given platform-owned station ids.
    ///
    /// The server answers per platform row without naming the source station,
    /// so callers must issue one request per station they display. Short-time
    /// results are cached so rapid navigation or polling does not spam the map
    /// API.
    ///
    /// @param stationIdsHex    platform-owned station ids, unsigned 16-digit hex
    /// @param maxCountPerPlatform maximum rows per platform, 4 is usually enough
    public static @Unmodifiable List<MtrArrival> fetchArrivals(List<String> stationIdsHex,
                                                               int maxCountPerPlatform) throws IOException {
        return fetchArrivals(base(), stationIdsHex, maxCountPerPlatform);
    }

    /// Same as {@link #fetchArrivals(List, int)} against an explicit map API base URL
    /// (test seam, see {@link #fetchSar(String)}).
    static @Unmodifiable List<MtrArrival> fetchArrivals(String base, List<String> stationIdsHex,
                                                        int maxCountPerPlatform) throws IOException {
        // the row limit shapes the reply, so it must take part in the cache key
        String key = (stationIdsHex.size() == 1 ? stationIdsHex.get(0) : stationIdsHex.toString())
                + "#" + maxCountPerPlatform;
        long now = System.currentTimeMillis();
        synchronized (arrivalsCache) {
            CachedArrivals cached = arrivalsCache.get(key);
            if (cached != null && now - cached.fetchedAt < ARRIVALS_FRESH_MS)
                return cached.rows();
        }

        JsonObject request = new JsonObject();
        JsonArray ids = new JsonArray();
        for (String id : stationIdsHex)
            ids.add(id);
        request.add("stationIdsHex", ids);
        request.addProperty("maxCountPerPlatform", maxCountPerPlatform);

        String body = doPostRetry(base, sarEndpoint("arrivals"), request.toString());
        JsonObject root = parse(body, "arrivals");
        JsonObject data = root.getAsJsonObject("data");

        List<MtrArrival> rows = new ArrayList<>();
        JsonArray arrivals = arrayOrEmpty(data, "arrivals");
        for (JsonElement element : arrivals) {
            JsonObject item = element.getAsJsonObject();
            String routeId = toHexId(optLong(item, "routeId"), "-");
            String platformId = toHexId(optLong(item, "platformId"), "-");
            List<String> cars = new ArrayList<>();
            // each car entry nests its vehicle id inside an object; malformed
            // entries are skipped instead of losing the whole row (M2)
            for (JsonElement car : arrayOrEmpty(item, "cars")) {
                if (!car.isJsonObject())
                    continue;
                JsonElement vehicleId = car.getAsJsonObject().get("vehicleId");
                if (vehicleId != null && vehicleId.isJsonPrimitive())
                    cars.add(vehicleId.getAsString());
            }
            rows.add(new MtrArrival(
                    JsonUtils.getString(item, "destination", ""),
                    fallbackLong(optLong(item, "arrival"), 0),
                    fallbackLong(optLong(item, "departure"), 0),
                    fallbackLong(optLong(item, "deviation"), 0),
                    JsonUtils.getBoolean(item, "realtime", false),
                    item.get("departureIndex") == null ? 0 : item.get("departureIndex").getAsInt(),
                    JsonUtils.getBoolean(item, "isTerminating", false),
                    routeId == null ? "-" : routeId,
                    JsonUtils.getString(item, "routeName", ""),
                    JsonUtils.getString(item, "routeNumber", ""),
                    JsonUtils.getInt(item, "routeColor", 0),
                    platformId == null ? "-" : platformId,
                    JsonUtils.getString(item, "platformName", "1"),
                    List.copyOf(cars)));
        }

        List<MtrArrival> result = List.copyOf(rows);
        synchronized (arrivalsCache) {
            arrivalsCache.put(key, new CachedArrivals(now, result));
        }
        return result;
    }

    /// Queries the server's directions finder for the single best path between two stations.
    ///
    /// The web asks for plain station names and answers one path, expressed as
    /// ordered ride and walk commits whose ids resolve against the network
    /// snapshot the page already holds, so no extra request is needed to display
    /// it. Station names accept the bilingual {@code "Chinese|English"} form the
    /// static network reported; the finder matches it against the full combined
    /// name or any single language part.
    ///
    /// @param fromStationName full station name to start from
    /// @param toStationName   full station name to arrive at
    /// @return the ordered legs of the single returned path
    /// @throws IOException when the finder does not answer or cannot plan between them
    public static @Unmodifiable MtrDirections fetchDirections(String fromStationName,
                                                              String toStationName) throws IOException {
        return fetchDirections(base(), fromStationName, toStationName);
    }

    /// Same as {@link #fetchDirections(String, String)} against an explicit map API base URL
    /// (test seam, see {@link #fetchSar(String)}).
    static MtrDirections fetchDirections(String base, String fromStationName,
                                         String toStationName) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("startTime", System.currentTimeMillis());
        request.addProperty("startStationName", fromStationName);
        request.addProperty("endStationName", toStationName);

        String body = doPostRetry(base, sarEndpoint("directions"), request.toString());
        JsonObject root = parse(body, "directions");
        JsonObject data = root.getAsJsonObject("data");

        long time = root.get("currentTime") == null ? System.currentTimeMillis()
                : root.get("currentTime").getAsLong();
        List<MtrDirectionsLeg> legs = new ArrayList<>();
        for (JsonElement element : arrayOrEmpty(data, "connections")) {
            JsonObject item = element.getAsJsonObject();
            String routeId = JsonUtils.getString(item, "routeId", "");
            legs.add(new MtrDirectionsLeg(
                    !routeId.isEmpty(),
                    routeId,
                    JsonUtils.getString(item, "startStationId", ""),
                    JsonUtils.getString(item, "endStationId", ""),
                    JsonUtils.getString(item, "startPlatformName", ""),
                    JsonUtils.getString(item, "endPlatformName", ""),
                    fallbackLong(optLong(item, "startTime"), 0),
                    fallbackLong(optLong(item, "endTime"), 0),
                    fallbackLong(optLong(item, "walkingDistance"), 0)));
        }
        return new MtrDirections(time, List.copyOf(legs));
    }

    /// Reads a repeated member as an array, tolerating a missing or null one.
    ///
    /// <p>The map API omits fields for empty collections (a route without
    /// durations, a station without connections), which must degrade to "no
    /// entries" instead of failing the whole page render.</p>
    private static JsonArray arrayOrEmpty(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null || !element.isJsonArray() ? new JsonArray() : element.getAsJsonArray();
    }

    private static @Nullable Long optLong(JsonObject object, String member) {
        if (!object.has(member))
            return null;
        try {
            return object.get(member).getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long getLong(JsonObject object, String member, long fallback) {
        return fallbackLong(optLong(object, member), fallback);
    }

    private static long fallbackLong(@Nullable Long value, long fallback) {
        return value == null ? fallback : value;
    }

    /// Chinese part of a bilingual {@code "Chinese|English"} name.
    public static String primaryName(String bilingual) {
        int split = bilingual.indexOf('|');
        return split < 0 ? bilingual : bilingual.substring(0, split);
    }

    /// Secondary (usually English) part of a bilingual name, or {@code null}.
    public static @Nullable String secondaryName(String bilingual) {
        int split = bilingual.indexOf('|');
        return split < 0 ? null : bilingual.substring(split + 1);
    }

    /// Trims a route name into display parts: main name and optional direction suffix.
    ///
    /// Names read like {@code "南际铁路|Nansai Rail||S2 -D"}; everything after the
    /// empty third segment describes the direction and is shown as-is.
    public static String routeDirection(String name) {
        String[] parts = name.split("\\|", -1);
        if (parts.length >= 3)
            return String.join("|", java.util.Arrays.copyOfRange(parts, 3, parts.length));
        if (parts.length == 2)
            return parts[1];
        return "";
    }

    private static @Nullable String trimToHex(@Nullable String id) {
        if (id == null)
            return null;
        id = id.trim();
        if (id.length() != 16)
            return null;
        return id.toUpperCase(java.util.Locale.ROOT);
    }

    /// Converts a signed 64-bit decimal id, as returned by {@code arrivals},
    /// into the unsigned 16-digit hex form used everywhere else.
    private static @Nullable String toHexId(@Nullable Long value, String fallback) {
        if (value == null)
            return fallback;
        return String.format("%016X", value);
    }

    private static String sarEndpoint(String endpoint) {
        String dimension = URLEncoder.encode(DIMENSION, StandardCharsets.UTF_8);
        return API_PATH + endpoint + "?dimension=" + dimension;
    }

    private static JsonObject parse(String body, String what) throws IOException {
        JsonObject element;
        try {
            element = JsonUtils.fromNonNullJson(JsonUtils.GSON, body, JsonObject.class);
            if (element == null)
                throw new IOException("MTR " + what + " is not a JSON object");
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Received malformed MTR " + what, e);
        }
        // a well-formed error envelope like {"code":500} carries no "data"
        // section; rejecting it here keeps the semantic failure a checked
        // exception with context instead of an NPE deep inside the array reads
        JsonElement data = element.get("data");
        if (data == null || !data.isJsonObject()) {
            JsonElement code = element.get("code");
            throw new IOException("MTR " + what + " answered without a data section"
                    + (code != null && code.isJsonPrimitive() ? " (code=" + code.getAsString() + ")" : ""));
        }
        return element;
    }

    /// Freshness of the map snapshot and the departure packs; arrivals are realtime, so they
    /// only get a short window and fall back to the last copy when the API is down.
    private static final Duration MAP_TTL = Duration.ofMinutes(2);

    /// Freshness of a cached arrivals query.
    private static final Duration ARRIVALS_TTL = Duration.ofSeconds(20);

    /// GET through the local cache; see {@link QLCache}.
    private static String doGetRetry(String base, String url) throws IOException {
        Duration ttl = url.contains("arrivals") ? ARRIVALS_TTL : MAP_TTL;
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return QLCache.fetch(URI.create(base + url), ttl).body();
            } catch (IOException e) {
                last = e;
                if (!sleepBetweenRetries())
                    throw new IOException("Interrupted while fetching " + url, e);
            }
        }
        throw new IOException("Failed to fetch " + url + " after " + RETRIES + " attempts", last);
    }

    private static String doPostRetry(String base, String url, String jsonBody) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return NetworkUtils.doPost(URI.create(base + url), jsonBody, "application/json");
            } catch (IOException e) {
                last = e;
                if (!sleepBetweenRetries())
                    throw new IOException("Interrupted while fetching " + url, e);
            }
        }
        throw new IOException("Failed to post " + url + " after " + RETRIES + " attempts", last);
    }

    private static boolean sleepBetweenRetries() {
        try {
            Thread.sleep(600);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
