import java.util.*;

public class OccupationMap {
    private static final String[] DAYS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes"};
    private static final int BLOCKS_PER_DAY = 5;

    private Map<String, Map<Integer, ScheduleEntry>> schedule;

    public OccupationMap() {
        schedule = new HashMap<>();
        initializeSchedule();
    }

    private void initializeSchedule() {
        for (String day : DAYS) {
            Map<Integer, ScheduleEntry> daySchedule = new HashMap<>();
            for (int block = 1; block <= BLOCKS_PER_DAY; block++) {
                daySchedule.put(block, null);
            }
            schedule.put(day, daySchedule);
        }
    }

    public boolean isBlockAvailable(String day, int block) {
        return schedule.get(day).get(block) == null;
    }

    public boolean isTeacherAvailable(String day, int block, String teacherRut) {
        ScheduleEntry entry = schedule.get(day).get(block);
        return entry == null || !entry.getTeacherRut().equals(teacherRut);
    }

    public void addScheduleEntry(String day, int block, ScheduleEntry entry) {
        if (!isBlockAvailable(day, block)) {
            throw new IllegalStateException("Block " + block + " on " + day + " is already occupied");
        }
        schedule.get(day).put(block, entry);
    }

    public List<TimeSlot> getAvailableTimeSlots() {
        List<TimeSlot> availableSlots = new ArrayList<>();
        for (String day : DAYS) {
            for (int block = 1; block <= BLOCKS_PER_DAY; block++) {
                if (isBlockAvailable(day, block)) {
                    availableSlots.add(new TimeSlot(day, block));
                }
            }
        }
        return availableSlots;
    }

    public Map<String, Map<Integer, ScheduleEntry>> getSchedule() {
        return schedule;
    }
}