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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for the MTR map API client, the timetable expansion and the local router.
///
/// <p>The JSON tests run against an in-process HTTP server through the package-private
/// {@code fetch*(String base)} seams, so no game server is touched. The graph tests
/// build synthetic {@link QLMtr.SarData} values instead.</p>
@NotNullByDefault
class QLMtrTest {

    private static final String STATION_1 = "0000000000000001";
    private static final String STATION_2 = "0000000000000002";
    private static final String STATION_3 = "0000000000000003";
    private static final String STATION_4 = "0000000000000004";
    private static final String ROUTE_A = "AAAAAAAAAAAAAAAA";
    private static final String ROUTE_B = "BBBBBBBBBBBBBBBB";

    private HttpServer server;
    private final Map<String, String> cannedResponses = new HashMap<>();
    private final List<String> requestedPaths = new ArrayList<>();
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mtr/api/map/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (requestedPaths) {
                requestedPaths.add(path);
            }
            String body = cannedResponses.get(path);
            if (body == null) {
                respond(exchange, 404, "{\"error\":\"no canned response for " + path + "\"}");
                return;
            }
            respond(exchange, 200, body);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        cannedResponses.clear();
        synchronized (requestedPaths) {
            requestedPaths.clear();
        }
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    /// Tests that a snapshot is normalised: ids trimmed to upper-case 16-digit hex,
    /// malformed ids and connections dropped, bilingual names split, direction suffix kept.
    @Test
    void parsesNetworkSnapshot() throws IOException {
        cannedResponses.put("/mtr/api/map/stations-and-routes", """
                {"currentTime":1000,"data":{
                  "stations":[
                    {"id":" %s ","name":"甲站|Alpha","color":1,"connections":["%s","broken",""]},
                    {"id":"%s","name":"乙站|Beta","connections":[]},
                    {"id":"00000000000000ff","name":"丙站"},
                    {"id":"nope","name":"被丢弃","connections":["%s"]}],
                  "routes":[
                    {"id":"000000000000000a","name":"A线|Line A||东行","color":5,"number":"A1",
                     "hidden":false,"circularState":"NONE",
                     "stations":[{"id":"%s","name":"1","dwellTime":500},{"id":"%s","name":"2","dwellTime":600}],
                     "durations":[60000]},
                    {"id":"x","name":"坏线路","stations":[],"durations":[]}]}}"""
                .formatted(STATION_1, STATION_2, STATION_2, STATION_1, STATION_1, STATION_2));

        QLMtr.SarData sar = QLMtr.fetchSar(baseUrl);

        assertEquals(1000, sar.time());
        assertEquals(3, sar.stations().size(), "the malformed station id must be skipped");
        assertEquals(STATION_1, sar.stations().get(0).id(), "ids are trimmed and upper-cased");
        assertEquals(List.of(STATION_2), sar.stations().get(0).connections(), "invalid connection ids are dropped");
        assertEquals("00000000000000FF", sar.stations().get(2).id());

        assertEquals(1, sar.routes().size());
        QLMtr.MtrRoute route = sar.routes().get(0);
        assertEquals("000000000000000A", route.id(), "route ids are upper-cased like station ids");
        assertEquals(List.of("1", "2"), route.stops().stream().map(QLMtr.MtrRouteStop::platformName).toList());
        assertEquals(500, route.stops().get(0).dwellTime());
        assertEquals(List.of(60000L), route.durations());
        assertEquals("A线", QLMtr.primaryName(route.name()));
        assertEquals("东行", QLMtr.routeDirection(route.name()));
        assertEquals("Alpha", QLMtr.secondaryName(sar.stations().get(0).name()));
        assertNull(QLMtr.secondaryName(sar.stations().get(2).name()));
    }

    /// Tests that members the API omits for empty collections degrade to empty lists
    /// instead of breaking the whole render, and that a missing server clock is tolerated.
    @Test
    void toleratesLeanResponses() throws IOException {
        cannedResponses.put("/mtr/api/map/stations-and-routes", """
                {"data":{"stations":[{"id":"%s","name":"X"}],
                          "routes":[{"id":"%s","name":"R",
                                     "stations":[{"id":"%s","name":"1"},{"id":"%s","name":"2"}]}]}}"""
                .formatted(STATION_1, ROUTE_A, STATION_1, STATION_2));

        QLMtr.SarData sar = QLMtr.fetchSar(baseUrl);

        assertEquals(1, sar.stations().size());
        assertEquals(List.of(), sar.stations().get(0).connections());
        QLMtr.MtrRoute route = sar.routes().get(0);
        assertEquals(List.of(), route.durations(), "missing durations become an empty list");
        assertEquals(0, route.stops().get(0).dwellTime());
        assertEquals("NONE", route.circularState());
        assertEquals("", route.number());
        assertTrue(sar.time() > 0, "falls back to the local clock when currentTime is absent");
    }

    /// Tests that departure packs are grouped per route and expanded into concrete
    /// times relative to the capture clock, then windowed, sorted and truncated.
    @Test
    void expandsDeparturePacks() throws IOException {
        cannedResponses.put("/mtr/api/map/departures", """
                {"currentTime":5000,"data":{"departures":[
                  {"id":"%s","departures":[
                    {"deviation":0,"departures":[40000,-90000,10000]},
                    {"departures":[70000]}]},
                  {"id":"bad","departures":[]}]}}"""
                .formatted(ROUTE_A));

        QLMtr.DeparturesData departures = QLMtr.fetchDepartures(baseUrl);
        assertEquals(5000, departures.time());
        assertEquals(1, departures.byRoute().size(), "routes with an unusable id are skipped");
        List<QLMtr.DepartureGroup> groups = departures.byRoute().get(ROUTE_A);
        assertEquals(2, groups.size());
        assertEquals(0, groups.get(1).deviation(), "absent deviation means on time");

        QLMtr.MtrRoute route = route(ROUTE_A, "R", false, "NONE",
                List.of(stop(STATION_1, "1"), stop(STATION_2, "1"), stop(STATION_3, "1")),
                List.of(10_000L, 20_000L));

        List<QLMtrTimetable.TimedDeparture> atThirdStop =
                QLMtrTimetable.nextAt(departures, route, 2, 5_000, 10);
        assertEquals(3, atThirdStop.size(), "the run older than the 60s grace window is dropped");
        assertEquals(15_000, atThirdStop.get(0).departureAt(), "results are sorted by departure time");
        assertEquals(45_000, atThirdStop.get(0).arrivalAt(), "arrival adds both travel segments");
        assertEquals(75_000, atThirdStop.get(2).departureAt());

        assertEquals(2, QLMtrTimetable.nextAt(departures, route, 2, 5_000, 2).size());
        assertEquals(List.of(), QLMtrTimetable.nextAt(departures,
                route(ROUTE_B, "other", false, "NONE", List.of(stop(STATION_1, "1")), List.of()), 0, 5_000, 5),
                "a route without a timetable pack has no departures");
    }

    /// Tests the real-time arrivals endpoint: signed decimal ids become the unsigned
    /// 16-digit hex form used everywhere else, and repeated lookups come from the
    /// short-lived cache so polling a platform does not spam the map API.
    @Test
    void parsesArrivalsAndCachesThem() throws IOException {
        cannedResponses.put("/mtr/api/map/arrivals", """
                {"data":{"arrivals":[
                  {"destination":"终点|Terminus","arrival":111,"departure":112,"deviation":3,
                   "realtime":true,"departureIndex":2,"isTerminating":false,
                   "routeId":1,"routeName":"R|Route","routeNumber":"R1","routeColor":7,
                   "platformId":2,"platformName":"3","cars":[{"vehicleId":"c1"},{"vehicleId":"c2"}]},
                  {"destination":"裸班次"}]}}""");

        List<QLMtr.MtrArrival> arrivals = QLMtr.fetchArrivals(baseUrl, List.of(STATION_4), 4);
        assertEquals(2, arrivals.size());
        assertEquals("0000000000000001", arrivals.get(0).routeId());
        assertEquals("0000000000000002", arrivals.get(0).platformId());
        assertEquals(List.of("c1", "c2"), arrivals.get(0).cars());
        assertEquals(111, arrivals.get(0).arrival());
        assertTrue(arrivals.get(0).realtime());
        assertEquals(0, arrivals.get(1).arrival(), "absent numeric members default to 0");
        assertEquals("-", arrivals.get(1).routeId());
        assertEquals("1", arrivals.get(1).platformName(), "platform name defaults to the first platform");

        int requestsAfterFirst = requestedPaths.size();
        List<QLMtr.MtrArrival> again = QLMtr.fetchArrivals(baseUrl, List.of(STATION_4), 4);
        assertEquals(arrivals, again);
        assertEquals(requestsAfterFirst, requestedPaths.size(), "the 4s cache must absorb the re-read");

        List<QLMtr.MtrArrival> richer = QLMtr.fetchArrivals(baseUrl, List.of(STATION_4), 10);
        assertEquals(arrivals, richer, "the canned reply is independent of the row limit");
        assertEquals(requestsAfterFirst + 1, requestedPaths.size(),
                "a different maxCountPerPlatform must be a different cache entry");
    }

    /// Tests that a syntactically valid but semantic error envelope - JSON without
    /// a "data" section - surfaces as a checked IOException with context instead
    /// of an NPE deep inside the array reads (M1).
    @Test
    void rejectsErrorEnvelopesWithoutData() {
        cannedResponses.put("/mtr/api/map/departures", "{\"code\":500,\"message\":\"boom\"}");

        IOException departures = assertThrows(IOException.class, () -> QLMtr.fetchDepartures(baseUrl));
        assertTrue(departures.getMessage().contains("code=500"), departures.getMessage());

        IOException arrivals = assertThrows(IOException.class,
                () -> QLMtr.fetchArrivals(baseUrl, List.of("0000000000000099"), 4));
        assertTrue(arrivals.getMessage().contains("without a data section"), arrivals.getMessage());
    }

    /// Tests that a malformed cars array degrades gracefully: non-object entries and
    /// entries without a usable vehicle id are skipped without losing the row (M2).
    @Test
    void skipsMalformedCarEntries() throws IOException {
        cannedResponses.put("/mtr/api/map/arrivals", """
                {"data":{"arrivals":[
                  {"destination":"终点","cars":[{"vehicleId":"c1"},"碎块",{"vehicleId":null},{"nope":1},42]}]}}""");

        List<QLMtr.MtrArrival> arrivals = QLMtr.fetchArrivals(baseUrl, List.of("0000000000000015"), 4);
        assertEquals(1, arrivals.size(), "the row survives its malformed car siblings");
        assertEquals(List.of("c1"), arrivals.get(0).cars());
    }

    /// Tests that the router picks the cheapest itinerary and merges consecutive
    /// stops of one route into a single leg.
    @Test
    void routesUseTheCheapestLine() {
        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2), station(STATION_3)),
                List.of(route(ROUTE_A, "慢线", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_2, "1"), stop(STATION_3, "1")),
                                List.of(60_000L, 60_000L)),
                        route(ROUTE_B, "快线", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_3, "1")),
                                List.of(30_000L))));

        List<QLMtrRouter.MtrPath> single = QLMtrRouter.findPaths(sar, STATION_1, STATION_3, 1);
        assertEquals(1, single.size());
        QLMtrRouter.MtrPath best = single.get(0);
        assertEquals(1, best.legs().size(), "the direct ride is one merged leg");
        assertEquals(ROUTE_B, best.legs().get(0).routeId());
        assertEquals(30_000, best.total());
        assertEquals(0, best.transfers());
        assertEquals("快线", best.legs().get(0).routeName());

        List<QLMtrRouter.MtrPath> alternatives = QLMtrRouter.findPaths(sar, STATION_1, STATION_3, 2);
        assertEquals(2, alternatives.size(), "banning the leading ride edge must surface the slower line");
        assertEquals(ROUTE_A, alternatives.get(1).legs().get(0).routeId());
        assertEquals(120_000, alternatives.get(1).total());
        assertEquals(2, alternatives.get(1).legs().get(0).stops());
    }

    /// Tests a two-line journey with a platform-to-platform transfer walk, including
    /// the changeover penalty, and that hidden lines never appear in a plan.
    @Test
    void chargesTransfersAndIgnoresHiddenLines() {
        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2, STATION_3), station(STATION_3, STATION_2),
                        station(STATION_4)),
                List.of(route(ROUTE_A, "A", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_2, "1")), List.of(60_000L)),
                        route(ROUTE_B, "B", false, "NONE",
                                List.of(stop(STATION_3, "1"), stop(STATION_4, "1")), List.of(30_000L)),
                        route("CCCCCCCCCCCCCCCC", "秘密线", true, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_4, "1")), List.of(1_000L))));

        List<QLMtrRouter.MtrPath> paths = QLMtrRouter.findPaths(sar, STATION_1, STATION_4, 3);
        assertEquals(1, paths.size(), "only the A-walk-B journey exists; the hidden line must stay out");
        QLMtrRouter.MtrPath path = paths.get(0);
        assertEquals(3, path.legs().size());
        assertTrue(path.legs().get(1).walk());
        assertEquals(1, path.transfers());
        assertEquals(60_000 + 120_000 + 30_000 + 180_000, path.total(),
                "ride times plus the 2 min walk and the 3 min changeover penalty");
    }

    /// Tests that a circular route closes its own loop, that the closing segment uses
    /// the average segment duration, and that degenerate queries yield no plan.
    @Test
    void closesCircularLoopsAndRejectsUnknownStations() {
        List<QLMtr.MtrRouteStop> stops = List.of(stop(STATION_1, "1"), stop(STATION_2, "1"), stop(STATION_3, "1"));
        List<Long> durations = List.of(10_000L, 20_000L, 30_000L);
        QLMtr.SarData loop = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2), station(STATION_3)),
                List.of(route(ROUTE_A, "环", false, "LOOP", stops, durations)));

        List<QLMtrRouter.MtrPath> around = QLMtrRouter.findPaths(loop, STATION_3, STATION_1, 1);
        assertEquals(1, around.size());
        assertEquals(20_000, around.get(0).legs().get(0).time(), "closing edge is the average segment duration");
        assertEquals(1, around.get(0).legs().get(0).stops());

        QLMtr.SarData open = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2), station(STATION_3)),
                List.of(route(ROUTE_A, "直线", false, "NONE", stops, durations)));
        List<QLMtrRouter.MtrPath> back = QLMtrRouter.findPaths(open, STATION_3, STATION_1, 1);
        assertEquals(1, back.size(), "without the loop the ride goes back through the middle station");
        assertEquals(30_000, back.get(0).legs().get(0).time());
        assertEquals(2, back.get(0).legs().get(0).stops());

        assertEquals(List.of(), QLMtrRouter.findPaths(open, STATION_1, "0000000000000099", 2));
        assertEquals(List.of(), QLMtrRouter.findPaths(open, STATION_1, STATION_1, 2),
                "origin equals destination: nothing to plan");
    }

    /// Tests that a station group follows the platform links the map API reports,
    /// transitively, and that a station without any link still groups by its name.
    @Test
    void groupsPlatformsOfOneStation() {
        QLMtr.SarData linked = new QLMtr.SarData(0,
                List.of(station(STATION_1, STATION_2), station(STATION_2, STATION_3),
                        station(STATION_3, STATION_2), station(STATION_4)),
                List.of());
        assertEquals(java.util.Set.of(STATION_1, STATION_2, STATION_3),
                QLMtr.stationGroupIds(linked, STATION_1), "the closure covers chained platforms");
        assertEquals(java.util.Set.of(STATION_4), QLMtr.stationGroupIds(linked, STATION_4),
                "an isolated station stays alone");
        assertEquals(java.util.Set.of("0000000000000099"), QLMtr.stationGroupIds(linked, "0000000000000099"),
                "an unknown id is returned unchanged rather than dropped");

        QLMtr.SarData unlinked = new QLMtr.SarData(0,
                List.of(new QLMtr.MtrStation(STATION_1, "甲站|Alpha", 0, List.of()),
                        new QLMtr.MtrStation(STATION_2, "甲站|Alpha", 0, List.of()),
                        new QLMtr.MtrStation(STATION_3, "乙站|Beta", 0, List.of())),
                List.of());
        assertEquals(java.util.Set.of(STATION_1, STATION_2), QLMtr.stationGroupIds(unlinked, STATION_1),
                "without links the Chinese name keeps the platforms together");

        QLMtr.SarData looped = new QLMtr.SarData(0,
                List.of(station(STATION_1, STATION_2), station(STATION_2, STATION_1)), List.of());
        assertEquals(java.util.Set.of(STATION_1, STATION_2), QLMtr.stationGroupIds(looped, STATION_1),
                "a mutual link must not loop forever");
    }

    /// Tests that the directions finder reply is read as the single ordered path:
    /// blank route ids are walks, epoch times are kept, and the plan card model is
    /// built over the network snapshot with walk stubs named by the picked endpoints.
    @Test
    void parsesDirectionsReplyAndShapesIt() throws IOException {
        cannedResponses.put("/mtr/api/map/directions", """
                {"currentTime":8000,"data":{"connections":[
                  {"routeId":"","startStationId":"","endStationId":"00000000000000A1",
                   "startPlatformName":"","endPlatformName":"1",
                   "startTime":9000,"endTime":21000,"walkingDistance":240},
                  {"routeId":"A135FAEFEFF1A370","startStationId":"00000000000000A1",
                   "endStationId":"00000000000000A2","startPlatformName":"1","endPlatformName":"1",
                   "startTime":21865,"endTime":25105,"walkingDistance":0},
                  {"routeId":"A487228B72DDA7F1","startStationId":"00000000000000A2",
                   "endStationId":"00000000000000A3","startPlatformName":"2","endPlatformName":"5",
                   "startTime":25105,"endTime":58790,"walkingDistance":0},
                  {"routeId":"","startStationId":"00000000000000A3","endStationId":"",
                   "startPlatformName":"5","endPlatformName":"",
                   "startTime":58790,"endTime":60629,"walkingDistance":86}]}}""");

        QLMtr.MtrDirections directions = QLMtr.fetchDirections(baseUrl, "四谷", "阳城");
        assertEquals(8000, directions.time());
        assertEquals(4, directions.legs().size());
        assertEquals(false, directions.legs().get(0).ride(), "a blank route id is a walk");
        assertEquals("A135FAEFEFF1A370", directions.legs().get(1).routeId());
        assertEquals("1", directions.legs().get(1).startPlatformName());
        assertEquals(3240, directions.legs().get(1).arrival() - directions.legs().get(1).departure());
        assertEquals(240, directions.legs().get(0).walkingDistance());

        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(new QLMtr.MtrStation("00000000000000A1", "甲站|Alpha", 0, List.of()),
                        new QLMtr.MtrStation("00000000000000A2", "乙站|Beta", 0, List.of()),
                        new QLMtr.MtrStation("00000000000000A3", "丙站|Gamma", 0, List.of())),
                List.of(route("A135FAEFEFF1A370", "一号线|Line 1", false, "NONE",
                                List.of(stop("00000000000000A1", "1"), stop("00000000000000A2", "1")),
                                List.of(3_240L)),
                        route("A487228B72DDA7F1", "二号线|Line 2", false, "NONE",
                                List.of(stop("00000000000000A2", "2"), stop("00000000000000A3", "5")),
                                List.of(33_685L))));

        QLMtrRouter.MtrPath path = QLMtrRouter.directionsToPath(sar, directions, "出发站", "到达站");
        assertTrue(path != null);
        assertEquals(4, path.legs().size());
        assertEquals("出发站", path.legs().get(0).fromStationName(),
                "an entry walk starts at the picked origin");
        assertEquals("甲站", path.legs().get(0).toStationName(),
                "walk ends are resolved through the snapshot");
        assertEquals("一号线", path.legs().get(1).routeName());
        assertEquals("二号线", path.legs().get(2).routeName());
        assertEquals(1, path.transfers(), "the line change between the two rides counts once");
        assertEquals(51629, path.total(), "the reply's own clock spans the whole path");
    }

    /// Tests the cleanups applied to web replies: same-station walks are dropped and
    /// a through-service the finder split into per-segment connections is merged
    /// back into one ride, while the reply's clock still spans the whole plan.
    @Test
    void cleansSameStationWalksAndSplitRides() throws IOException {
        cannedResponses.put("/mtr/api/map/directions", """
                {"currentTime":9000,"data":{"connections":[
                  {"routeId":"","startStationId":"","endStationId":"00000000000000B1",
                   "startPlatformName":"","endPlatformName":"1",
                   "startTime":0,"endTime":12000,"walkingDistance":300},
                  {"routeId":"1111111111111111","startStationId":"00000000000000B1",
                   "endStationId":"00000000000000B2","startPlatformName":"1","endPlatformName":"2",
                   "startTime":12000,"endTime":42000,"walkingDistance":0},
                  {"routeId":"1111111111111111","startStationId":"00000000000000B2",
                   "endStationId":"00000000000000B3","startPlatformName":"2","endPlatformName":"1",
                   "startTime":42000,"endTime":72000,"walkingDistance":0},
                  {"routeId":"","startStationId":"00000000000000B3","endStationId":"00000000000000B3",
                   "startPlatformName":"1","endPlatformName":"2",
                   "startTime":72000,"endTime":75000,"walkingDistance":90},
                  {"routeId":"2222222222222222","startStationId":"00000000000000B3",
                   "endStationId":"00000000000000B4","startPlatformName":"2","endPlatformName":"1",
                   "startTime":75000,"endTime":105000,"walkingDistance":0},
                  {"routeId":"","startStationId":"00000000000000B4","endStationId":"",
                   "startPlatformName":"1","endPlatformName":"",
                   "startTime":105000,"endTime":117000,"walkingDistance":120}]}}""");

        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(new QLMtr.MtrStation("00000000000000B1", "甲站|Alpha", 0, List.of()),
                        new QLMtr.MtrStation("00000000000000B2", "乙站|Beta", 0, List.of()),
                        new QLMtr.MtrStation("00000000000000B3", "丙站|Gamma", 0, List.of()),
                        new QLMtr.MtrStation("00000000000000B4", "丁站|Delta", 0, List.of())),
                List.of(route("1111111111111111", "一号线", false, "NONE",
                                List.of(stop("00000000000000B1", "1"), stop("00000000000000B2", "2"),
                                        stop("00000000000000B3", "1")),
                                List.of(30_000L, 30_000L)),
                        route("2222222222222222", "二号线", false, "NONE",
                                List.of(stop("00000000000000B3", "2"), stop("00000000000000B4", "1")),
                                List.of(30_000L))));

        QLMtr.MtrDirections directions = QLMtr.fetchDirections(baseUrl, "甲站", "丁站");
        QLMtrRouter.MtrPath path = QLMtrRouter.directionsToPath(sar, directions, "出发站", "到达站");
        assertTrue(path != null);
        assertEquals(4, path.legs().size(),
                "the same-station walk vanishes; the entry and exit walks survive");
        assertEquals("一号线", path.legs().get(1).routeName());
        assertEquals("丙站", path.legs().get(1).toStationName(),
                "the split connections of the through-service are one ride again");
        assertEquals(60_000, path.legs().get(1).time(), "both segments count into the merged ride");
        assertEquals("二号线", path.legs().get(2).routeName());
        assertEquals("丁站", path.legs().get(3).fromStationName());
        assertEquals("到达站", path.legs().get(3).toStationName());
        assertEquals(1, path.transfers(), "only the line change counts, not the dropped walk");
        assertEquals(117_000, path.total(), "the reply clock still covers the walking time");
    }

    /// Tests that the transfer count is the number of line changes: two rides on
    /// different lines chained without a walk leg still count one transfer, and
    /// the changeover penalty is part of the reported total.
    @Test
    void countsLineChangesWithoutWalkLegs() {
        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2), station(STATION_3)),
                List.of(route(ROUTE_A, "A", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_2, "1")), List.of(60_000L)),
                        route(ROUTE_B, "B", false, "NONE",
                                List.of(stop(STATION_2, "1"), stop(STATION_3, "1")), List.of(30_000L))));

        List<QLMtrRouter.MtrPath> paths = QLMtrRouter.findPaths(sar, STATION_1, STATION_3, 1);
        assertEquals(1, paths.size());
        QLMtrRouter.MtrPath path = paths.get(0);
        assertEquals(2, path.legs().size(), "no walk leg exists between the two platforms");
        assertEquals(1, path.transfers(), "the ride-to-ride line change is the transfer");
        assertEquals(60_000 + 30_000 + 180_000, path.total());
    }

    /// Tests that parallel variants of one line - same name, same segments, other
    /// route ids - collapse into one plan instead of filling the alternative list.
    @Test
    void mergesParallelVariantsOfOneLine() {
        QLMtr.SarData sar = new QLMtr.SarData(0,
                List.of(station(STATION_1), station(STATION_2), station(STATION_3)),
                List.of(route(ROUTE_A, "X", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_2, "1"),
                                        stop(STATION_3, "1")), List.of(60_000L, 60_000L)),
                        route(ROUTE_B, "X", false, "NONE",
                                List.of(stop(STATION_1, "1"), stop(STATION_2, "1"),
                                        stop(STATION_3, "1")), List.of(60_000L, 60_000L))));

        List<QLMtrRouter.MtrPath> merged = QLMtrRouter.findPaths(sar, STATION_1, STATION_3, 3);
        assertEquals(1, merged.size(),
                "both variants of line X ride identical name-and-segment rows: one plan remains");
        assertEquals(2, merged.get(0).legs().get(0).stops());
        assertEquals(0, merged.get(0).transfers());
    }

    private static QLMtr.MtrStation station(String id, String... connections) {
        return new QLMtr.MtrStation(id, id + "|en", 0, List.of(connections));
    }

    private static QLMtr.MtrRouteStop stop(String stationId, String platformName) {
        return new QLMtr.MtrRouteStop(stationId, platformName, 0);
    }

    private static QLMtr.MtrRoute route(String id, String name, boolean hidden, String circularState,
                                        List<QLMtr.MtrRouteStop> stops, List<Long> durations) {
        return new QLMtr.MtrRoute(id, name, 0, "", hidden, circularState, stops, durations);
    }
}
