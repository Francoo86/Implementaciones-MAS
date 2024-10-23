import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import jade.core.behaviours.*;
import jade.wrapper.State;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;
import java.util.*;
import jade.wrapper.State;

public class Aplicacion extends Agent {
    private int profesorActual = 0;
    private List<AgentController> profesoresControllers = new ArrayList<>();
    private Map<String, AgentController> salasControllers = new HashMap<>();
    //private static final long TIEMPO_ESPERA = 360000; // 6 minutos
    //private static final long TIEMPO_ENTRE_PROFESORES = 3000; // 3 segundos

    protected void setup() {
        System.out.println("Agente Aplicacion iniciado");

        try {
            // Aumentar límite de resultados del DF
            System.setProperty("jade_domain_df_maxresult", "-1");

            // Carga datos desde archivos JSON
            JSONArray profesoresJson = loadJsonArray("profesores.json");
            JSONArray salasJson = loadJsonArray("salas.json");

            // Crear e iniciar agentes sala primero
            System.out.println("Iniciando creación de agentes sala...");
            salasControllers = createSalaAgents(getContainerController(), salasJson);
            Thread.sleep(2000);     // Esperar a que las salas estén inicializadas

            // Configurar total de solicitudes para las salas
            int totalAsignaturas = calculateTotalAsignaturas(profesoresJson);
            setTotalSolicitudesForSalas(totalAsignaturas);

            // Crear agentes profesor (sin iniciarlos)
            System.out.println("Creando agentes profesor...");
            profesoresControllers = createProfesorAgents(getContainerController(), profesoresJson);
            Thread.sleep(2000);   // Esperar un momento para asegurar la creación

            // Iniciar solo el primer profesor
            if (!profesoresControllers.isEmpty()) {
                profesoresControllers.get(0).start();
                System.out.println("Primer profesor iniciado");
            }

            // Agregar comportamiento para monitorear el progreso
            addBehaviour(new MonitorearProgresoComportamiento(this, 5000));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private JSONArray loadJsonArray(String filename) throws Exception {
        JSONParser parser = new JSONParser();
        try (FileReader reader = new FileReader(filename)) {
            return (JSONArray) parser.parse(reader);
        }
    }

    private Map<String, AgentController> createSalaAgents(AgentContainer container, JSONArray salasJson)
            throws StaleProxyException {
        Map<String, AgentController> controllers = new HashMap<>(); // Código de sala -> Agente Sala

        // Crear agentes sala.
        for (Object obj : salasJson) {
            JSONObject salaJson = (JSONObject) obj;
            String codigo = (String) salaJson.get("Codigo");
            String jsonString = salaJson.toJSONString();

            Object[] salaArgs = {jsonString};
            AgentController sala = container.createNewAgent(
                    "Sala" + codigo,
                    "AgenteSala",
                    salaArgs
            );
            sala.start();
            // Agregar a mapa de controladores
            controllers.put(codigo, sala);
            System.out.println("Agente Sala " + codigo + " creado e iniciado con JSON: " + jsonString);
        }

        return controllers;
    }

    private List<AgentController> createProfesorAgents(AgentContainer container, JSONArray profesoresJson)
            throws StaleProxyException {
        List<AgentController> controllers = new ArrayList<>();  // Lista de controladores de agentes profesor.
        int profesorCount = 0;      // Contador de agentes profesor.

        // Crea un objeto JSON con los datos del profesor (nombre, RUT, asignaturas).
        for (Object obj : profesoresJson) {
            JSONObject profesorJson = (JSONObject) obj;
            String nombre = (String) profesorJson.get("Nombre");

            JSONObject profesorCompleto = new JSONObject();
            profesorCompleto.put("Nombre", nombre);
            profesorCompleto.put("RUT", profesorJson.get("RUT"));
            profesorCompleto.put("Asignaturas", profesorJson.get("Asignaturas"));

            String jsonString = profesorCompleto.toJSONString();
            Object[] profesorArgs = {jsonString, profesorCount};

            AgentController profesor = container.createNewAgent(
                    "Profesor" + (++profesorCount),
                    "AgenteProfesor",
                    profesorArgs
            );
            controllers.add(profesor);  // Agrega el controlador a la lista.
            System.out.println("Agente Profesor " + nombre + " creado (no iniciado) con JSON: " + jsonString);
        }

        return controllers;
    }

    private int calculateTotalAsignaturas(JSONArray profesoresJson) {
        int total = 0;
        for (Object obj : profesoresJson) {
            JSONObject profesor = (JSONObject) obj;
            JSONArray asignaturas = (JSONArray) profesor.get("Asignaturas");
            total += asignaturas.size();
        }
        System.out.println("Total de asignaturas a asignar: " + total);
        return total;
    }

    private void setTotalSolicitudesForSalas(int totalAsignaturas) {
        for (Map.Entry<String, AgentController> entry : salasControllers.entrySet()) {
            try {
                SalaInterface salaInterface = entry.getValue().getO2AInterface(SalaInterface.class);
                if (salaInterface != null) {
                    salaInterface.setTotalSolicitudes(totalAsignaturas);
                    System.out.println("Total de solicitudes establecido para sala: " + entry.getKey());
                }
            } catch (StaleProxyException e) {
                System.out.println("Error al configurar solicitudes para sala: " + entry.getKey());
                e.printStackTrace();
            }
        }
    }

    private class MonitorearProgresoComportamiento extends TickerBehaviour {
        private static final int MAX_INACTIVITY = 60;
        private int inactivityCounter = 0;

        public MonitorearProgresoComportamiento(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            try {
                if (profesorActual < profesoresControllers.size()) {
                    AgentController currentProfesor = profesoresControllers.get(profesorActual);

                    try {
                        int state = currentProfesor.getState().getCode();
                        if (state == 4) { // AGENT_KILLED
                            System.out.println("Profesor " + profesorActual + " completó su proceso");
                            Thread.sleep(2000); // Wait for JSON updates

                            profesorActual++;
                            inactivityCounter = 0;

                            if (profesorActual < profesoresControllers.size()) {
                                Thread.sleep(3000);
                                profesoresControllers.get(profesorActual).start();
                                System.out.println("Iniciando siguiente profesor: " + profesorActual);
                            }
                        } else {
                            inactivityCounter++;
                            if (inactivityCounter >= MAX_INACTIVITY) {
                                System.out.println("Profesor " + profesorActual +
                                        " inactivo. Forzando finalización");
                                currentProfesor.kill();
                                inactivityCounter = 0;
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("Error monitoreando profesor " + profesorActual);
                        e.printStackTrace();
                    }
                } else {
                    // All professors finished
                    finalizarProceso();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Finaliza el proceso de asignación de horarios y genera los archivos JSON finales.
        private void finalizarProceso() {
            try {
                Thread.sleep(2000);
                System.out.println("Generando archivos JSON finales");
                ProfesorHorarioJSON.getInstance().generarArchivoJSON();
                SalaHorarioJSON.getInstance().generarArchivoJSON();
                stop();
                myAgent.doDelete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    protected void takeDown() {
        System.out.println("Aplicación finalizada. Archivos JSON generados.");
    }

    // Metodo principal para iniciar la aplicación.
    public static void main(String[] args) {
        // Establecer el límite de resultados del DF antes de crear el contenedor
        System.setProperty("jade_domain_df_maxresult", "-1");

        Runtime rt = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.GUI, "true");

        AgentContainer mainContainer = rt.createMainContainer(profile);

        try {
            mainContainer.createNewAgent("Aplicacion", "Aplicacion", new Object[]{}).start();
        } catch (StaleProxyException e) {
            e.printStackTrace();
        }
    }
}