import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class OccupancyMap {
    private Map<String, Map<Integer, AsignacionInfo>> dayBlockMap;
    private final String owner; // Teacher name or Room code
    
    public OccupancyMap(String owner) {
        this.owner = owner;
        this.dayBlockMap = new HashMap<>();
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            Map<Integer, AsignacionInfo> blockMap = new HashMap<>();
            for (int i = 1; i <= 5; i++) {
                blockMap.put(i, null);
            }
            dayBlockMap.put(dia, blockMap);
        }
    }

    public static class AsignacionInfo {
        String asignatura;
        String roomOrTeacher; // Room code for teacher map, Teacher name for room map
        int valoracion;

        public AsignacionInfo(String asignatura, String roomOrTeacher, int valoracion) {
            this.asignatura = asignatura;
            this.roomOrTeacher = roomOrTeacher;
            this.valoracion = valoracion;
        }
    }

    public boolean isBlockAvailable(String day, int block) {
        Map<Integer, AsignacionInfo> blockMap = dayBlockMap.get(day);
        return blockMap != null && blockMap.get(block) == null;
    }

    public boolean assignTimeSlot(String day, int block, String asignatura, 
                                String roomOrTeacher, int valoracion) {
        Map<Integer, AsignacionInfo> blockMap = dayBlockMap.get(day);
        if (blockMap != null && blockMap.get(block) == null) {
            blockMap.put(block, new AsignacionInfo(asignatura, roomOrTeacher, valoracion));
            return true;
        }
        return false;
    }

    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        json.put("owner", owner);
        
        JSONArray assignments = new JSONArray();
        for (Map.Entry<String, Map<Integer, AsignacionInfo>> dayEntry : dayBlockMap.entrySet()) {
            for (Map.Entry<Integer, AsignacionInfo> blockEntry : dayEntry.getValue().entrySet()) {
                AsignacionInfo info = blockEntry.getValue();
                if (info != null) {
                    JSONObject assignment = new JSONObject();
                    assignment.put("dia", dayEntry.getKey());
                    assignment.put("bloque", blockEntry.getKey());
                    assignment.put("asignatura", info.asignatura);
                    assignment.put("roomOrTeacher", info.roomOrTeacher);
                    assignment.put("valoracion", info.valoracion);
                    assignments.add(assignment);
                }
            }
        }
        json.put("assignments", assignments);
        return json;
    }

    public int getAvailableBlockCount() {
        int count = 0;
        for (Map<Integer, AsignacionInfo> dayMap : dayBlockMap.values()) {
            for (AsignacionInfo info : dayMap.values()) {
                if (info == null) count++;
            }
        }
        return count;
    }
}