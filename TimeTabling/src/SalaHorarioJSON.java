import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class SalaHorarioJSON {
    private static SalaHorarioJSON instance;
    private Map<String, JSONObject> salasHorarios;

    private SalaHorarioJSON() {
        salasHorarios = new ConcurrentHashMap<>();
    }

    public static synchronized SalaHorarioJSON getInstance() {
        if (instance == null) {
            instance = new SalaHorarioJSON();
        }
        return instance;
    }

    public void agregarHorarioSala(String nombre, Map<String, List<String>> horario) {
        JSONObject salaJSON = new JSONObject();
        salaJSON.put("Nombre", nombre);

        JSONArray asignaturasJSON = new JSONArray();
        for (Map.Entry<String, List<String>> entry : horario.entrySet()) {
            String dia = entry.getKey();
            List<String> bloques = entry.getValue();
            for (int i = 0; i < bloques.size(); i++) {
                String asignatura = bloques.get(i);
                if (!asignatura.isEmpty()) {
                    JSONObject asignaturaJSON = new JSONObject();
                    asignaturaJSON.put("Nombre", asignatura);
                    asignaturaJSON.put("Bloque", i + 1);
                    asignaturaJSON.put("Dia", dia);
                    asignaturasJSON.add(asignaturaJSON);
                }
            }
        }
        salaJSON.put("Asignatura", asignaturasJSON);
        salasHorarios.put(nombre, salaJSON);
    }

    public void generarArchivoJSON() {
        JSONArray jsonArray = new JSONArray();
        jsonArray.addAll(salasHorarios.values());

        try (FileWriter file = new FileWriter("Salidas_salas.json")) {
            file.write(formatJSONString(jsonArray.toJSONString()));
            System.out.println("Archivo salidas_salas.json generado exitosamente.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String formatJSONString(String jsonString) {
        StringBuilder sb = new StringBuilder();
        int indentLevel = 0;
        boolean inQuotes = false;

        for (char c : jsonString.toCharArray()) {
            switch (c) {
                case '{':
                case '[':
                    sb.append(c).append("\n");
                    indentLevel++;
                    addIndentation(sb, indentLevel);
                    break;
                case '}':
                case ']':
                    sb.append("\n");
                    indentLevel--;
                    addIndentation(sb, indentLevel);
                    sb.append(c);
                    break;
                case ',':
                    sb.append(c);
                    if (!inQuotes) {
                        sb.append("\n");
                        addIndentation(sb, indentLevel);
                    }
                    break;
                case ':':
                    sb.append(c).append(" ");
                    break;
                case '\"':
                    inQuotes = !inQuotes;
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private void addIndentation(StringBuilder sb, int indentLevel) {
        for (int i = 0; i < indentLevel; i++) {
            sb.append("  ");
        }
    }
}