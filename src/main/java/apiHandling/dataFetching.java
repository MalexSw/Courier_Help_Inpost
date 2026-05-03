package apiHandling;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import classes.PointsResponse;
import reactor.core.publisher.Mono;

public class dataFetching {

    public static PointsResponse fetchPointsData(WebClient webClient) {
        return fetchPointsData(webClient, false);
    }

    public static PointsResponse fetchPointsData(WebClient webClient, boolean verbose) {
        // Set UTF-8 for console output
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);

        String url = "https://api-global-points.easypack24.net/v1/points";
        PointsResponse state = new PointsResponse();
        Mono<String> response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class);

        // Block and process the response
        String jsonData = response.block();

        try {
            ObjectMapper mapper = new ObjectMapper();
            PointsResponse pointsResponse = mapper.readValue(jsonData, PointsResponse.class);

            state.count = pointsResponse.count;
            state.page = pointsResponse.page;
            state.total_pages = pointsResponse.total_pages;
            state.items = new java.util.ArrayList<>(pointsResponse.items);

            out.println("Total Automates: " + pointsResponse.count);
            out.println("Page: " + pointsResponse.page + " / " + pointsResponse.total_pages);
            if (verbose) {
                out.println("\n--- Automates List ---\n");

                for (PointsResponse.Point point : pointsResponse.items) {
                    out.println("Name: " + point.name);
                    out.println("Hours: " + point.opening_hours);
                    out.println("Coordinates: " + point.location);
                    out.println("Address: " + point.address);
                    out.println("Country: " + point.country);
                    out.println("Href: " + point.href);
                    out.println("Type: " + point.type);
                    out.println("Payment Description: " + point.payment_point_descr);
                    out.println("Address Details: " + point.address_details);
                    out.println("---");
                }
            }
            out.flush();
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
            e.printStackTrace();
        }
        return state;
    }
}
