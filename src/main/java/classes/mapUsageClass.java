package classes;

import java.util.List;

public class mapUsageClass {

    public List<String> cities;
    public List<String> provinces;
    public List<AddressInCity> addresses;

    public static class AddressInCity {

        public String post_code;
        public String street;
        public String building_number;

        @Override
        public String toString() {
            return "In city{"
                    + ", post_code='" + post_code + '\''
                    + ", street='" + street + '\''
                    + ", building_number='" + building_number + '\''
                    + '}'
                    + "\n";
        }
    }
}
