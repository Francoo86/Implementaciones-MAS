public class Assignment {
    private String name;
    private int hours;
    private int capacity;

    public Assignment(String name, int hours, int capacity) {
        this.name = name;
        this.hours = hours;
        this.capacity = capacity;
    }

    // Getters and setters
    public String getName() { return name; }
    public int getHours() { return hours; }
    public int getCapacity() { return capacity; }
}