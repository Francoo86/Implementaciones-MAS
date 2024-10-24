public class SatisfactionCalculator {
    public static int calculateSatisfaction(int roomCapacity, int subjectVacancies) {
        if (roomCapacity == subjectVacancies) {
            return 10;
        } else if (roomCapacity > subjectVacancies) {
            return 5;
        } else {
            return 3;
        }
    }
}