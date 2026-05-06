package com.example;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import apiHandling.dataFetching;
import classes.PointsResponse;
import routing.RoutePlanner;

/**
 * Spring Boot application to fetch data from API
 */
@SpringBootApplication
public class App {

    private static final Scanner SCANNER = new Scanner(System.in);

    public static void main(String[] args) {
        ApplicationContext context = SpringApplication.run(App.class, args);

        // Get WebClient bean
        WebClient webClient = context.getBean(WebClient.Builder.class).build();

        // Call the API
        PointsResponse state = dataFetching.fetchPointsData(webClient, false);

        List<RoutePlanner.CityCount> cityCounts = RoutePlanner.getCityCounts(state.items);
        RoutePlanner.printTopCities(cityCounts, 25);

        Selection selection = resolveSelection(args, cityCounts);
        if (selection == null) {
            System.out.println("No city selected.");
            return;
        }

        List<PointsResponse.Point> cityPoints = RoutePlanner.getCityPoints(state.items, selection.city,
                selection.province, selection.limit);

        if (cityPoints.size() < 2) {
            System.out.println("Not enough points for city: " + selection.city + ". Need at least 2.");
            return;
        }

        printCityPoints(cityPoints);
        int startIndex = promptStartPointIndex(cityPoints);
        PointsResponse.Point startPoint = cityPoints.get(startIndex);

        Map<PointsResponse.Point, Integer> packagesByLocker = promptPackagesForLockers(cityPoints,
                selection.packagesPerLocker);
        LocalTime startTime = promptStartTime(selection.startTime);

        RoutePlanner.printRouteForCity(cityPoints, selection.city, selection.province, selection.osmFile,
                selection.graphCache, selection.transportType, packagesByLocker, selection.minutesPerPackage,
                startPoint, startTime, selection.routingMode, webClient, selection.graphhopperKey,
                selection.graphhopperUrl);
    }

    private static Selection resolveSelection(String[] args, List<RoutePlanner.CityCount> cityCounts) {
        if (cityCounts == null || cityCounts.isEmpty()) {
            return null;
        }

        int index = 0;
        RoutePlanner.CityCount chosen = null;
        String cityInput = null;
        String provinceInput = null;

        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            String first = args[0].trim();
            if (isNumber(first)) {
                int pos = Integer.parseInt(first);
                if (pos >= 1 && pos <= cityCounts.size()) {
                    chosen = cityCounts.get(pos - 1);
                }
                index = 1;
            } else {
                String[] parts = splitCityProvince(first);
                cityInput = parts[0];
                if (parts.length > 1) {
                    provinceInput = parts[1];
                }
                index = 1;
            }
        } else {
            chosen = promptCitySelection(cityCounts);
        }

        if (chosen == null) {
            if (provinceInput == null && index < args.length && args[index] != null
                    && !args[index].trim().isEmpty() && !isNumber(args[index])) {
                provinceInput = args[index].trim();
                index++;
            }

            chosen = findCityCount(cityCounts, cityInput, provinceInput);
        }

        if (chosen == null) {
            return null;
        }

        RouteOptions options = resolveRouteOptions(args, index);
        return new Selection(chosen.city, chosen.province, options.limit, options.osmFile, options.graphCache,
                options.transportType, options.packagesPerLocker, options.minutesPerPackage, options.startTime,
                options.routingMode, options.graphhopperKey, options.graphhopperUrl);
    }

    private static RoutePlanner.CityCount promptCitySelection(List<RoutePlanner.CityCount> cityCounts) {
        if (cityCounts == null || cityCounts.isEmpty()) {
            return null;
        }

        String input = readLine("Choose city by number or name (City or City,Province): ");

        if (isNumber(input)) {
            int index = Integer.parseInt(input);
            if (index >= 1 && index <= cityCounts.size()) {
                return cityCounts.get(index - 1);
            }
        }

        if (!input.isEmpty()) {
            String[] parts = splitCityProvince(input);
            RoutePlanner.CityCount chosen = findCityCount(cityCounts, parts[0], parts.length > 1 ? parts[1] : null);
            if (chosen != null) {
                return chosen;
            }
        }

        return cityCounts.get(0);
    }

    private static RoutePlanner.CityCount findCityCount(List<RoutePlanner.CityCount> cityCounts, String city,
            String province) {
        if (cityCounts == null || cityCounts.isEmpty() || city == null || city.trim().isEmpty()) {
            return null;
        }

        RoutePlanner.CityCount best = null;
        for (RoutePlanner.CityCount item : cityCounts) {
            if (!city.equalsIgnoreCase(item.city)) {
                continue;
            }

            if (province != null && !province.trim().isEmpty()) {
                if (province.equalsIgnoreCase(item.province)) {
                    return item;
                }
                continue;
            }

            if (best == null || item.count > best.count) {
                best = item;
            }
        }

        return best;
    }

    private static String[] splitCityProvince(String input) {
        String[] parts = input.split("[|,]", 2);
        if (parts.length == 0) {
            return new String[] { input };
        }

        parts[0] = parts[0].trim();
        if (parts.length > 1) {
            parts[1] = parts[1].trim();
        }
        return parts;
    }

    private static boolean isNumber(String value) {
        return value != null && value.matches("\\d+");
    }

    private static RouteOptions resolveRouteOptions(String[] args, int startIndex) {
        RouteOptions options = new RouteOptions();

        boolean hasFlags = hasFlagArgs(args, startIndex);
        if (hasFlags) {
            parseFlagOptions(args, startIndex, options);
            return options;
        }

        int index = startIndex;
        if (index < args.length && isNumber(args[index])) {
            options.limit = Integer.parseInt(args[index]);
            index++;
        }

        if (index < args.length && args[index] != null && !args[index].trim().isEmpty()) {
            options.osmFile = args[index];
            index++;
        }

        if (index < args.length && args[index] != null && !args[index].trim().isEmpty()) {
            options.graphCache = args[index];
        }

        promptForRouteOptions(options);
        return options;
    }

    private static boolean hasFlagArgs(String[] args, int startIndex) {
        if (args == null) {
            return false;
        }

        for (int i = startIndex; i < args.length; i++) {
            if (args[i] != null && args[i].startsWith("--")) {
                return true;
            }
        }

        return false;
    }

    private static void parseFlagOptions(String[] args, int startIndex, RouteOptions options) {
        for (int i = startIndex; i < args.length; i++) {
            String token = args[i];
            if (token == null || !token.startsWith("--")) {
                continue;
            }

            if (i + 1 >= args.length) {
                break;
            }

            String value = args[++i];
            if (value == null) {
                continue;
            }

            switch (token) {
                case "--limit":
                    options.limit = parseNonNegativeInt(value, options.limit);
                    break;
                case "--transport":
                    if (!value.trim().isEmpty()) {
                        options.transportType = value.trim();
                    }
                    break;
                case "--packages":
                    options.packagesPerLocker = parseNonNegativeInt(value, options.packagesPerLocker);
                    break;
                case "--service-min":
                    options.minutesPerPackage = parseNonNegativeDouble(value, options.minutesPerPackage);
                    break;
                case "--start-time":
                    if (!value.trim().isEmpty()) {
                        options.startTime = value.trim();
                    }
                    break;
                case "--routing":
                    if (!value.trim().isEmpty()) {
                        options.routingMode = value.trim();
                    }
                    break;
                case "--gh-key":
                    if (!value.trim().isEmpty()) {
                        options.graphhopperKey = value.trim();
                    }
                    break;
                case "--gh-url":
                    if (!value.trim().isEmpty()) {
                        options.graphhopperUrl = value.trim();
                    }
                    break;
                case "--osm":
                    options.osmFile = value.trim();
                    break;
                case "--graph-cache":
                    options.graphCache = value.trim();
                    break;
                default:
                    break;
            }
        }
    }

    private static void promptForRouteOptions(RouteOptions options) {
        String routing = promptRoutingMode(options.routingMode);
        if (!routing.isEmpty()) {
            options.routingMode = routing;
        }

        if ("api".equalsIgnoreCase(options.routingMode)) {
            String key = promptGraphHopperKey(options.graphhopperKey);
            if (!key.isEmpty()) {
                options.graphhopperKey = key;
            }

            String url = promptGraphHopperUrl(options.graphhopperUrl);
            if (!url.isEmpty()) {
                options.graphhopperUrl = url;
            }
        }

        String transport = promptTransportType(options.transportType);
        if (!transport.isEmpty()) {
            options.transportType = transport;
        }

        // options.packagesPerLocker = promptInt(
        // "Default packages per locker [default " + options.packagesPerLocker + "]: ",
        // options.packagesPerLocker);
        options.minutesPerPackage = promptDouble(
                "Minutes per package [default " + formatNumber(options.minutesPerPackage) + "]: ",
                options.minutesPerPackage);
    }

    private static void printCityPoints(List<PointsResponse.Point> points) {
        System.out.println("\n--- Lockers in selected city ---\n");
        for (int i = 0; i < points.size(); i++) {
            PointsResponse.Point point = points.get(i);
            System.out.println((i + 1) + ". " + formatPointLabel(point));
        }
        System.out.println();
    }

    private static int promptStartPointIndex(List<PointsResponse.Point> points) {
        String input = readLine("Choose start locker by number or name [default 1]: ");
        if (input.isEmpty()) {
            return 0;
        }

        if (isNumber(input)) {
            int index = Integer.parseInt(input);
            if (index >= 1 && index <= points.size()) {
                return index - 1;
            }
        }

        int byName = findPointIndexByName(points, input);
        return byName >= 0 ? byName : 0;
    }

    private static Map<PointsResponse.Point, Integer> promptPackagesForLockers(
            List<PointsResponse.Point> points,
            int defaultPackages) {
        Map<PointsResponse.Point, Integer> packagesByLocker = new HashMap<>();
        System.out.println("\n--- Packages per locker ---\n");
        for (int i = 0; i < points.size(); i++) {
            PointsResponse.Point point = points.get(i);
            String prompt = "Packages for locker " + (i + 1) + " (" + formatPointLabel(point)
                    + ") [default " + defaultPackages + "]: ";
            int packages = promptInt(prompt, defaultPackages);
            packagesByLocker.put(point, packages);
        }
        return packagesByLocker;
    }

    private static String formatPointLabel(PointsResponse.Point point) {
        if (point == null) {
            return "Unknown";
        }

        String name = point.name == null || point.name.trim().isEmpty() ? "Unknown" : point.name.trim();
        String address = formatAddress(point.address_details);
        if (address.isEmpty()) {
            return name;
        }

        return name + " - " + address;
    }

    private static String formatAddress(PointsResponse.AddressDetails details) {
        if (details == null) {
            return "";
        }

        String street = safeTrim(details.street);
        String building = safeTrim(details.building_number);
        String postCode = safeTrim(details.post_code);

        StringBuilder builder = new StringBuilder();
        if (!street.isEmpty()) {
            builder.append(street);
        }
        if (!building.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(building);
        }
        if (!postCode.isEmpty()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(postCode);
        }

        return builder.toString();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static LocalTime promptStartTime(String defaultValue) {
        String input = readLine("Start time (HH:mm) [default " + defaultValue + "]: ");
        if (input.isEmpty()) {
            input = defaultValue;
        }

        LocalTime parsed = parseLocalTime(input);
        if (parsed == null) {
            parsed = parseLocalTime(defaultValue);
        }

        return parsed == null ? LocalTime.of(8, 0) : parsed;
    }

    private static LocalTime parseLocalTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static int findPointIndexByName(List<PointsResponse.Point> points, String input) {
        if (points == null || input == null) {
            return -1;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < points.size(); i++) {
            PointsResponse.Point point = points.get(i);
            if (point != null && point.name != null && trimmed.equalsIgnoreCase(point.name.trim())) {
                return i;
            }
        }

        return -1;
    }

    private static String promptTransportType(String defaultValue) {
        String input = readLine(
                "Transport type (car/bike/foot) [default " + defaultValue + "]: ");
        return input.isEmpty() ? defaultValue : input;
    }

    private static String promptRoutingMode(String defaultValue) {
        String input = readLine(
                "Routing mode (local/api) [default " + defaultValue + "]: ");
        if (input.isEmpty()) {
            return defaultValue;
        }

        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if ("api".equals(normalized) || "local".equals(normalized)) {
            return normalized;
        }

        return defaultValue;
    }

    private static String promptGraphHopperKey(String defaultValue) {
        String input = readLine("GraphHopper API key (leave empty to use local): ");
        return input.isEmpty() ? defaultValue : input;
    }

    private static String promptGraphHopperUrl(String defaultValue) {
        String input = readLine("GraphHopper API URL [default " + defaultValue + "]: ");
        return input.isEmpty() ? defaultValue : input;
    }

    private static int promptInt(String prompt, int defaultValue) {
        String input = readLine(prompt);
        if (input.isEmpty()) {
            return defaultValue;
        }

        return parseNonNegativeInt(input, defaultValue);
    }

    private static double promptDouble(String prompt, double defaultValue) {
        String input = readLine(prompt);
        if (input.isEmpty()) {
            return defaultValue;
        }

        return parseNonNegativeDouble(input, defaultValue);
    }

    private static String readLine(String prompt) {
        System.out.print(prompt);
        return SCANNER.nextLine().trim();
    }

    private static int parseNonNegativeInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(0, parsed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseNonNegativeDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value.trim());
            return Math.max(0.0, parsed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String formatNumber(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static class Selection {

        private final String city;
        private final String province;
        private final int limit;
        private final String osmFile;
        private final String graphCache;
        private final String transportType;
        private final int packagesPerLocker;
        private final double minutesPerPackage;
        private final String startTime;
        private final String routingMode;
        private final String graphhopperKey;
        private final String graphhopperUrl;

        private Selection(String city, String province, int limit, String osmFile, String graphCache,
                String transportType, int packagesPerLocker, double minutesPerPackage, String startTime,
                String routingMode, String graphhopperKey, String graphhopperUrl) {
            this.city = city;
            this.province = province;
            this.limit = limit;
            this.osmFile = osmFile;
            this.graphCache = graphCache;
            this.transportType = transportType;
            this.packagesPerLocker = packagesPerLocker;
            this.minutesPerPackage = minutesPerPackage;
            this.startTime = startTime;
            this.routingMode = routingMode;
            this.graphhopperKey = graphhopperKey;
            this.graphhopperUrl = graphhopperUrl;
        }
    }

    private static class RouteOptions {

        private int limit = 10;
        private String osmFile = "lib/poland-260430.osm.pbf";
        private String graphCache = "graph-cache";
        private String transportType = "car";
        private int packagesPerLocker = 1;
        private double minutesPerPackage = 0.0;
        private String startTime = "08:00";
        private String routingMode = "local";
        private String graphhopperKey = "";
        private String graphhopperUrl = "https://graphhopper.com/api/1/route";
    }

}
