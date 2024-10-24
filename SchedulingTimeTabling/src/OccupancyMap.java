// OccupancyMap.java
import java.util.*;

public class OccupancyMap {
    private Map<String, Map<Integer, Map<Integer, Assignment>>> schedule; // day -> block -> assignment

    public OccupancyMap() {
        schedule = new HashMap<>();
        initializeSchedule();
    }

    private void initializeSchedule() {
        String[] days = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
        for (String day : days) {
            Map<Integer, Map<Integer, Assignment>> daySchedule = new HashMap<>();
            for (int block = 1; block <= 5; block++) {
                daySchedule.put(block, new HashMap<>());
            }
            schedule.put(day, daySchedule);
        }
    }

    public boolean isAvailable(String day, int block) {
        return schedule.get(day).get(block).isEmpty();
    }

    public void addAssignment(Assignment assignment, TimeSlot timeSlot) {
        schedule.get(timeSlot.getDay()).get(timeSlot.getBlock()).put(timeSlot.getBlock(), assignment);
    }

    public Map<String, Map<Integer, Map<Integer, Assignment>>> getSchedule() {
        return schedule;
    }
}