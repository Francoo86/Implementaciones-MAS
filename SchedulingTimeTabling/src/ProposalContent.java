public class ProposalContent {
    public String roomCode;
    public TimeSlot timeSlot;
    public int satisfaction;
    public String subjectName;

    public ProposalContent(String roomCode, TimeSlot timeSlot, int satisfaction) {
        this.roomCode = roomCode;
        this.timeSlot = timeSlot;
        this.satisfaction = satisfaction;
    }
}