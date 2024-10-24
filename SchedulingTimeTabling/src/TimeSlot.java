public class TimeSlot {
    private String day;
    private int block;

    public TimeSlot(String day, int block) {
        this.day = day;
        this.block = block;
    }

    // Getters
    public String getDay() { return day; }
    public int getBlock() { return block; }

    @Override
    public String toString() {
        return "TimeSlot{day='" + day + "', block=" + block + "}";
    }
}