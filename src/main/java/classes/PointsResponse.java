package classes;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PointsResponse {

    public int count;
    public int page;
    public int per_page;
    public int total_pages;
    public List<Point> items;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Point {

        public String name;
        public String country;
        public String href;
        public List<String> type;
        public String opening_hours;
        public Location location;
        public Address address;
        public AddressDetails address_details;

        public String payment_point_descr;

        @Override
        public String toString() {
            return "Point{"
                    + "name='" + name + '\''
                    + ", country='" + country + '\''
                    + ", href='" + href + '\''
                    + ", type=" + type
                    + ", opening_hours='" + opening_hours + '\''
                    + ", location=" + location
                    + ", address=" + address
                    + ", address_details=" + address_details
                    + ", payment_point_descr='" + payment_point_descr + '\''
                    + '}'
                    + "\n";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {

        public double longitude;
        public double latitude;

        @Override
        public String toString() {
            return "(" + latitude + ", " + longitude + ")";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {

        public String line1;
        public String line2;

        @Override
        public String toString() {
            return line1 + ", " + line2;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AddressDetails {

        public String city;
        public String province;
        public String post_code;
        public String street;
        public String building_number;

        @Override
        public String toString() {
            return "AddressDetails{"
                    + "city='" + city + '\''
                    + ", province='" + province + '\''
                    + ", post_code='" + post_code + '\''
                    + ", street='" + street + '\''
                    + ", building_number='" + building_number + '\''
                    + '}';
        }
    }

}
