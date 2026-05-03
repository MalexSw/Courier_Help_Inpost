package routing;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;

import classes.PointsResponse;

public class RoutePlanner {

    public static void printRouteForCity(List<PointsResponse.Point> cityPoints, String city, String province,
            String osmFile, String graphCache, String transportType,
            Map<PointsResponse.Point, Integer> packagesByLocker, double minutesPerPackage,
            PointsResponse.Point startPoint, LocalTime startTime, String routingMode,
            WebClient webClient, String graphhopperKey, String graphhopperUrl) {
        if (cityPoints == null || cityPoints.size() < 2) {
            System.out.println("Not enough points for city: " + city + ". Need at least 2.");
            return;
        }

        String vehicle = normalizeTransportType(transportType);
        RoutingMode mode = resolveRoutingMode(routingMode);
        boolean useApi = mode == RoutingMode.API;

        if (useApi && (webClient == null || graphhopperKey == null || graphhopperKey.trim().isEmpty())) {
            System.out.println("GraphHopper API key missing. Falling back to local routing.");
            useApi = false;
        }

        String cacheLocation = null;
        GraphHopper hopper = null;
        if (!useApi) {
            cacheLocation = resolveGraphCacheLocation(graphCache, vehicle);
            hopper = new GraphHopper();
            hopper.setOSMFile(osmFile);
            hopper.setGraphHopperLocation(cacheLocation);
            hopper.setProfiles(new Profile(vehicle).setVehicle(vehicle).setWeighting("fastest"));
            hopper.importOrLoad();
        }

        double totalDistanceMeters = 0;
        long totalTimeMs = 0;
        long totalServiceSeconds = 0;

        String areaLabel = formatAreaLabel(city, province);
        double safeMinutesPerPackage = Math.max(0.0, minutesPerPackage);
        LocalTime currentTime = startTime == null ? LocalTime.of(8, 0) : startTime;

        List<String[]> summaryRows = new ArrayList<>();
        summaryRows.add(new String[]{"City", areaLabel});
        summaryRows.add(new String[]{"Transport", vehicle});
        summaryRows.add(new String[]{"Routing mode", useApi ? "api" : "local"});
        if (useApi) {
            summaryRows.add(new String[]{"GraphHopper API", resolveApiUrl(graphhopperUrl)});
        } else {
            summaryRows.add(new String[]{"Graph cache", cacheLocation});
        }
        summaryRows.add(new String[]{"Minutes per package", formatMinutes(safeMinutesPerPackage)});
        summaryRows.add(new String[]{"Start time", formatTime(currentTime)});
        summaryRows.add(new String[]{"Start locker", formatPointLabel(startPoint)});

        System.out.println("\n--- Route Statistics ---");
        printKeyValueTable("Route Summary", summaryRows);

        List<PointsResponse.Point> orderedPoints = orderByNearestNeighbor(cityPoints, startPoint);

        System.out.println();
        printScheduleHeader();

        PointsResponse.Point firstStop = orderedPoints.get(0);
        long firstServiceSeconds = getServiceSeconds(firstStop, packagesByLocker, safeMinutesPerPackage);
        printScheduleRow(1, formatPointLabel(firstStop), formatTime(currentTime),
                Integer.toString(getPackages(firstStop, packagesByLocker)),
                formatMinutes(firstServiceSeconds / 60.0),
                formatTime(currentTime.plusSeconds(firstServiceSeconds)), "-", "-");
        currentTime = currentTime.plusSeconds(firstServiceSeconds);
        totalServiceSeconds += firstServiceSeconds;

        for (int i = 0; i < orderedPoints.size() - 1; i++) {
            PointsResponse.Point from = orderedPoints.get(i);
            PointsResponse.Point to = orderedPoints.get(i + 1);

            double segmentDistanceMeters;
            long segmentTimeMs;

            if (useApi) {
                ApiPath path = fetchApiPath(webClient, graphhopperUrl, graphhopperKey, vehicle, from, to);
                if (path == null) {
                    System.out.println("Route error from " + from.name + " to " + to.name + ": API error");
                    System.out.println("Schedule stopped due to routing error.");
                    break;
                }

                segmentDistanceMeters = path.distance;
                segmentTimeMs = path.time;
            } else {
                GHRequest req = new GHRequest(
                        from.location.latitude,
                        from.location.longitude,
                        to.location.latitude,
                        to.location.longitude)
                        .setProfile(vehicle)
                        .setLocale(Locale.US);

                GHResponse res = hopper.route(req);

                if (res.hasErrors()) {
                    System.out.println("Route error from " + from.name + " to " + to.name + ": " + res.getErrors());
                    System.out.println("Schedule stopped due to routing error.");
                    break;
                }

                ResponsePath path = res.getBest();
                segmentDistanceMeters = path.getDistance();
                segmentTimeMs = path.getTime();
            }

            totalDistanceMeters += segmentDistanceMeters;
            totalTimeMs += segmentTimeMs;

            long driveSeconds = Math.round(segmentTimeMs / 1000.0);
            currentTime = currentTime.plusSeconds(driveSeconds);

            long serviceSeconds = getServiceSeconds(to, packagesByLocker, safeMinutesPerPackage);
            printScheduleRow(i + 2, formatPointLabel(to), formatTime(currentTime),
                    Integer.toString(getPackages(to, packagesByLocker)),
                    formatMinutes(serviceSeconds / 60.0),
                    formatTime(currentTime.plusSeconds(serviceSeconds)),
                    formatDistanceKm(segmentDistanceMeters),
                    formatMinutes(segmentTimeMs / 60000.0));
            currentTime = currentTime.plusSeconds(serviceSeconds);
            totalServiceSeconds += serviceSeconds;
        }

        double totalServiceMin = totalServiceSeconds / 60.0;
        double totalDrivingMin = totalTimeMs / 60000.0;

        List<String[]> totalsRows = new ArrayList<>();
        totalsRows.add(new String[]{"Lockers", Integer.toString(orderedPoints.size())});
        totalsRows.add(new String[]{"Total packages", Integer.toString(sumPackages(packagesByLocker))});
        totalsRows.add(new String[]{"Total distance (km)", formatDistanceKm(totalDistanceMeters)});
        totalsRows.add(new String[]{"Total driving time (min)", formatMinutes(totalDrivingMin)});
        totalsRows.add(new String[]{"Total service time (min)", formatMinutes(totalServiceMin)});
        totalsRows.add(new String[]{"Total time with service (min)", formatMinutes(totalDrivingMin + totalServiceMin)});
        totalsRows.add(new String[]{"End time", formatTime(currentTime)});

        System.out.println();
        printKeyValueTable("Totals", totalsRows);

        if (hopper != null) {
            hopper.close();
        }
    }

    public static List<PointsResponse.Point> getCityPoints(List<PointsResponse.Point> points, String city,
            String province, int limit) {
        return filterCityPoints(points, city, province, limit);
    }

    private static List<PointsResponse.Point> filterCityPoints(List<PointsResponse.Point> points, String city,
            String province, int limit) {
        List<PointsResponse.Point> result = new ArrayList<>();

        if (points == null || city == null) {
            return result;
        }

        for (PointsResponse.Point point : points) {
            if (point == null || point.location == null || point.address_details == null) {
                continue;
            }

            if (point.location.latitude == 0.0 && point.location.longitude == 0.0) {
                continue;
            }

            if (city.equalsIgnoreCase(point.address_details.city)
                    && provinceMatches(point.address_details.province, province)) {
                result.add(point);
            }

            if (result.size() >= limit) {
                break;
            }
        }

        return result;
    }

    private static boolean provinceMatches(String pointProvince, String selectedProvince) {
        if (selectedProvince == null || selectedProvince.trim().isEmpty()) {
            return true;
        }

        if (pointProvince == null) {
            return false;
        }

        return selectedProvince.trim().equalsIgnoreCase(pointProvince.trim());
    }

    private static String formatAreaLabel(String city, String province) {
        if (province == null || province.trim().isEmpty()) {
            return city;
        }

        return city + " (" + province + ")";
    }

    private static List<PointsResponse.Point> orderByNearestNeighbor(List<PointsResponse.Point> points,
            PointsResponse.Point startPoint) {
        List<PointsResponse.Point> ordered = new ArrayList<>();

        if (points == null || points.isEmpty()) {
            return ordered;
        }

        List<PointsResponse.Point> remaining = new ArrayList<>(points);
        PointsResponse.Point current = startPoint;
        if (current == null || !remaining.remove(current)) {
            current = remaining.remove(0);
        }
        ordered.add(current);

        while (!remaining.isEmpty()) {
            int bestIndex = 0;
            double bestDistance = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                PointsResponse.Point candidate = remaining.get(i);
                double distance = haversineMeters(current, candidate);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            current = remaining.remove(bestIndex);
            ordered.add(current);
        }

        return ordered;
    }

    private static double haversineMeters(PointsResponse.Point a, PointsResponse.Point b) {
        double lat1 = Math.toRadians(a.location.latitude);
        double lon1 = Math.toRadians(a.location.longitude);
        double lat2 = Math.toRadians(b.location.latitude);
        double lon2 = Math.toRadians(b.location.longitude);

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);

        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

        return 6371000.0 * c;
    }

    public static List<CityCount> getCityCounts(List<PointsResponse.Point> points) {
        Map<String, Integer> counts = new HashMap<>();
        List<CityCount> result = new ArrayList<>();

        if (points == null) {
            return result;
        }

        for (PointsResponse.Point point : points) {
            if (point == null || point.address_details == null || point.address_details.city == null) {
                continue;
            }

            String city = safeTrim(point.address_details.city);
            String province = safeTrim(point.address_details.province);
            if (city.isEmpty()) {
                continue;
            }

            String key = city + "|" + province;
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String city = parts[0];
            String province = parts.length > 1 ? parts[1] : "";
            result.add(new CityCount(city, province, entry.getValue()));
        }

        result.sort((a, b) -> Integer.compare(b.count, a.count));
        return result;
    }

    public static void printTopCities(List<CityCount> cityCounts, int limit) {
        if (cityCounts == null || cityCounts.isEmpty()) {
            System.out.println("No city data available.");
            return;
        }

        int max = Math.min(limit, cityCounts.size());
        System.out.println("\n--- Cities by parcel count ---\n");
        for (int i = 0; i < max; i++) {
            CityCount item = cityCounts.get(i);
            String label = formatAreaLabel(item.city, item.province);
            System.out.println((i + 1) + ". " + label + " (" + item.count + ")");
        }
    }

    public static class CityCount {

        public final String city;
        public final String province;
        public final int count;

        public CityCount(String city, String province, int count) {
            this.city = city;
            this.province = province;
            this.count = count;
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTransportType(String transportType) {
        if (transportType == null || transportType.trim().isEmpty()) {
            return "car";
        }

        String value = transportType.trim().toLowerCase(Locale.ROOT);
        if ("car".equals(value) || "auto".equals(value)) {
            return "car";
        }

        if ("bike".equals(value) || "bicycle".equals(value)) {
            return "bike";
        }

        if ("foot".equals(value) || "walk".equals(value)) {
            return "foot";
        }

        return "car";
    }

    private static String resolveGraphCacheLocation(String graphCache, String vehicle) {
        String base = (graphCache == null || graphCache.trim().isEmpty())
                ? "graph-cache"
                : graphCache.trim();
        return base + "-" + vehicle;
    }

    private static RoutingMode resolveRoutingMode(String routingMode) {
        if (routingMode == null || routingMode.trim().isEmpty()) {
            return RoutingMode.LOCAL;
        }

        String value = routingMode.trim().toLowerCase(Locale.ROOT);
        if ("api".equals(value) || "cloud".equals(value)) {
            return RoutingMode.API;
        }

        return RoutingMode.LOCAL;
    }

    private static String resolveApiUrl(String graphhopperUrl) {
        if (graphhopperUrl == null || graphhopperUrl.trim().isEmpty()) {
            return "https://graphhopper.com/api/1/route";
        }

        return graphhopperUrl.trim();
    }

    private static ApiPath fetchApiPath(WebClient webClient, String graphhopperUrl, String graphhopperKey,
            String vehicle, PointsResponse.Point from, PointsResponse.Point to) {
        String apiUrl = resolveApiUrl(graphhopperUrl);
        String requestUrl = buildGraphHopperUrl(apiUrl, graphhopperKey, vehicle, from, to);

        try {
            String json = webClient.get()
                    .uri(requestUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (json == null || json.trim().isEmpty()) {
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            ApiResponse response = mapper.readValue(json, ApiResponse.class);
            if (response == null || response.paths == null || response.paths.isEmpty()) {
                return null;
            }

            return response.paths.get(0);
        } catch (Exception ex) {
            System.out.println("GraphHopper API error: " + ex.getMessage());
            return null;
        }
    }

    private static String buildGraphHopperUrl(String apiUrl, String apiKey, String vehicle,
            PointsResponse.Point from, PointsResponse.Point to) {
        StringBuilder builder = new StringBuilder(apiUrl);
        if (!apiUrl.contains("?")) {
            builder.append("?");
        } else if (!apiUrl.endsWith("?") && !apiUrl.endsWith("&")) {
            builder.append("&");
        }

        builder.append("point=")
                .append(from.location.latitude)
                .append(",")
                .append(from.location.longitude)
                .append("&point=")
                .append(to.location.latitude)
                .append(",")
                .append(to.location.longitude)
                .append("&profile=")
                .append(vehicle)
                .append("&key=")
                .append(apiKey);

        return builder.toString();
    }

    private enum RoutingMode {
        LOCAL,
        API
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiResponse {

        public List<ApiPath> paths;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiPath {

        public double distance;
        public long time;
    }

    private static long getServiceSeconds(PointsResponse.Point point,
            Map<PointsResponse.Point, Integer> packagesByLocker,
            double minutesPerPackage) {
        int packages = getPackages(point, packagesByLocker);
        double serviceMinutes = Math.max(0.0, minutesPerPackage) * packages;
        return Math.round(serviceMinutes * 60.0);
    }

    private static int getPackages(PointsResponse.Point point,
            Map<PointsResponse.Point, Integer> packagesByLocker) {
        if (packagesByLocker == null || point == null) {
            return 0;
        }
        Integer value = packagesByLocker.get(point);
        return value == null ? 0 : Math.max(0, value);
    }

    private static String formatPointLabel(PointsResponse.Point point) {
        if (point == null) {
            return "Unknown";
        }

        String name = point.name == null || point.name.trim().isEmpty() ? "Unknown" : point.name.trim();
        String street = point.address_details == null ? "" : safeTrim(point.address_details.street);
        String building = point.address_details == null ? "" : safeTrim(point.address_details.building_number);
        String postCode = point.address_details == null ? "" : safeTrim(point.address_details.post_code);

        StringBuilder builder = new StringBuilder();
        builder.append(name);
        if (!street.isEmpty()) {
            builder.append(" - ").append(street);
            if (!building.isEmpty()) {
                builder.append(" ").append(building);
            }
            if (!postCode.isEmpty()) {
                builder.append(", ").append(postCode);
            }
        }

        return builder.toString();
    }

    private static String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String formatMinutes(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatDistanceKm(double meters) {
        return String.format(Locale.US, "%.2f", meters / 1000.0);
    }

    private static int sumPackages(Map<PointsResponse.Point, Integer> packagesByLocker) {
        if (packagesByLocker == null || packagesByLocker.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Integer value : packagesByLocker.values()) {
            if (value != null && value > 0) {
                total += value;
            }
        }
        return total;
    }

    private static void printKeyValueTable(String title, List<String[]> rows) {
        int keyWidth = "Metric".length();
        int valueWidth = "Value".length();

        for (String[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            keyWidth = Math.max(keyWidth, safeLength(row[0]));
            valueWidth = Math.max(valueWidth, safeLength(row[1]));
        }

        String border = buildBorder(new int[]{keyWidth, valueWidth});

        System.out.println(title);
        System.out.println(border);
        printRow(new int[]{keyWidth, valueWidth}, new String[]{"Metric", "Value"});
        System.out.println(border);
        for (String[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            printRow(new int[]{keyWidth, valueWidth}, new String[]{row[0], row[1]});
        }
        System.out.println(border);
    }

    private static void printScheduleHeader() {
        int[] widths = scheduleWidths();
        String border = buildBorder(widths);
        System.out.println("Schedule");
        System.out.println(border);
        printRow(widths, new String[]{"#", "Locker", "Arrive", "Packages", "Service(min)",
            "Depart", "Seg(km)", "Seg(min)"});
        System.out.println(border);
    }

    private static void printScheduleRow(int index, String locker, String arrive, String packages,
            String serviceMin, String depart, String segKm, String segMin) {
        int[] widths = scheduleWidths();
        printRow(widths, new String[]{Integer.toString(index), locker, arrive, packages, serviceMin,
            depart, segKm, segMin});
    }

    private static int[] scheduleWidths() {
        return new int[]{3, 36, 5, 8, 11, 5, 8, 8};
    }

    private static String buildBorder(int[] widths) {
        StringBuilder builder = new StringBuilder();
        builder.append('+');
        for (int width : widths) {
            builder.append(repeat('-', width + 2)).append('+');
        }
        return builder.toString();
    }

    private static void printRow(int[] widths, String[] values) {
        StringBuilder builder = new StringBuilder();
        builder.append('|');
        for (int i = 0; i < widths.length; i++) {
            String value = i < values.length ? values[i] : "";
            builder.append(' ')
                    .append(formatCell(value, widths[i]))
                    .append(' ')
                    .append('|');
        }
        System.out.println(builder.toString());
    }

    private static String formatCell(String value, int width) {
        String safe = value == null ? "" : value;
        if (safe.length() > width) {
            if (width <= 3) {
                safe = safe.substring(0, width);
            } else {
                safe = safe.substring(0, width - 3) + "...";
            }
        }
        return padRight(safe, width);
    }

    private static String padRight(String value, int width) {
        StringBuilder builder = new StringBuilder(value == null ? "" : value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String repeat(char ch, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(ch);
        }
        return builder.toString();
    }
}
