public class ScheduleEntry {
    private String subjectName;
    private String teacherRut;
    private String roomCode;
    private int satisfaction;

    public ScheduleEntry(String subjectName, String teacherRut, String roomCode, int satisfaction) {
        this.subjectName = subjectName;
        this.teacherRut = teacherRut;
        this.roomCode = roomCode;
        this.satisfaction = satisfaction;
    }

    // Getters
    public String getSubjectName() { return subjectName; }
    public String getTeacherRut() { return teacherRut; }
    public String getRoomCode() { return roomCode; }
    public int getSatisfaction() { return satisfaction; }
}