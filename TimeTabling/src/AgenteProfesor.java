import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private List<Asignatura> asignaturas;
    private JSONObject horarioJSON;
    private int asignaturaActual = 0;
    private Map<String, Set<String>> horarioOcupado;
    private int orden;
    private static final long TIMEOUT_RESPUESTA = 30000; // 30 seconds timeout
    private AtomicBoolean isNegotiating = new AtomicBoolean(false);
    private int reintentos = 0;
    private static final int MAX_REINTENTOS = 3;
    private OccupancyMap occupancyMap;

    protected void setup() {
        try {
            Object[] args = getArguments();
            if (args != null && args.length > 1) {
                String jsonString = (String) args[0];
                orden = (Integer) args[1];
                loadFromJsonString(jsonString);
            }

            // Initialize schedules and occupancy map
            horarioJSON = new JSONObject();
            horarioJSON.put("Asignaturas", new JSONArray());
            horarioOcupado = new HashMap<>();
            occupancyMap = new OccupancyMap(nombre);

            System.out.println("Agente Profesor " + nombre + " creado. Orden: " + orden +
                    ". Asignaturas totales: " + asignaturas.size());

            // Register with DF
            registrarEnDF();

            // Add shutdown hook for cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    cleanup();
                } catch (Exception e) {
                    System.err.println("Error during cleanup for profesor " + nombre + ": " + e.getMessage());
                }
            }));

            if (orden == 0) {
                iniciarNegociaciones();
            } else {
                esperarTurno();
            }

        } catch (Exception e) {
            System.err.println("Error initializing profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
            doDelete();
        }
    }

    private void registrarEnDF() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("profesor");
            sd.setName("Profesor" + orden);
            dfd.addServices(sd);
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            System.err.println("Error registering profesor " + nombre + " in DF: " + fe.getMessage());
            fe.printStackTrace();
            doDelete();
        }
    }

    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            nombre = (String) jsonObject.get("Nombre");
            rut = (String) jsonObject.get("RUT");

            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");

            if (asignaturasJson != null) {
                for (Object asignaturaObj : asignaturasJson) {
                    JSONObject asignaturaJson = (JSONObject) asignaturaObj;
                    if (asignaturaJson != null) {
                        String nombreAsignatura = (String) asignaturaJson.get("Nombre");
                        int horas = getIntValue(asignaturaJson, "Horas", 0);
                        int vacantes = getIntValue(asignaturaJson, "Vacantes", 0);
                        asignaturas.add(new Asignatura(nombreAsignatura, 0, 0, horas, vacantes));
                    }
                }
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private int getIntValue(JSONObject json, String key, int defaultValue) {
        Object value = json.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private void esperarTurno() {
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null && msg.getContent().equals("START")) {
                    System.out.println("Profesor " + nombre + " (orden " + orden + ") recibió señal de inicio");
                    iniciarNegociaciones();
                    myAgent.removeBehaviour(this);
                } else {
                    block();
                }
            }
        });
    }

    private void iniciarNegociaciones() {
        if (isNegotiating.compareAndSet(false, true)) {
            System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciando negociaciones para " +
                    asignaturas.size() + " asignaturas");

            addBehaviour(new NegociarAsignaturasBehaviour());

            // Add timeout behavior
            addBehaviour(new WakerBehaviour(this, TIMEOUT_RESPUESTA) {
                protected void onWake() {
                    if (asignaturaActual < asignaturas.size()) {
                        System.out.println("Timeout en negociaciones para profesor " + nombre +
                                ". Forzando finalización...");
                        finalizarNegociaciones();
                    }
                }
            });
        }
    }

    private class NegociarAsignaturasBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private List<ACLMessage> propuestas = new ArrayList<>();
        private int respuestasEsperadas = 0;
        private long tiempoInicio;
        private boolean negociacionCompletada = false;
        private int intentosActuales = 0;
        private static final int MAX_INTENTOS_POR_ASIGNATURA = 2;
        private final Map<String, Long> pendingProposals = new HashMap<>();

        public void action() {
            try {
                switch (step) {
                    case 0:
                        if (asignaturaActual < asignaturas.size()) {
                            if (intentosActuales < MAX_INTENTOS_POR_ASIGNATURA) {
                                solicitarPropuestas();
                                tiempoInicio = System.currentTimeMillis();
                                step = 1;
                            } else {
                                System.out.println("Máximos intentos alcanzados para asignatura " + 
                                    asignaturas.get(asignaturaActual).getNombre() + 
                                    ". Pasando a la siguiente.");
                                asignaturaActual++;
                                intentosActuales = 0;
                            }
                        } else {
                            negociacionCompletada = true;
                        }
                        break;

                    case 1:
                        handleProposals();
                        break;

                    case 2:
                        if (!propuestas.isEmpty()) {
                            evaluarPropuestas();
                        }
                        step = 3;
                        break;

                    case 3:
                        prepararSiguienteAsignatura();
                        break;
                }
            } catch (Exception e) {
                System.err.println("Error en negociación para profesor " + nombre + 
                    ", asignatura " + asignaturaActual + ": " + e.getMessage());
                e.printStackTrace();
                handleNegotiationError();
            }
        }

        private void handleProposals() {
            ACLMessage reply = myAgent.receive(mt);
            if (reply != null) {
                switch (reply.getPerformative()) {
                    case ACLMessage.PROPOSE:
                        handleProposal(reply);
                        break;
                    case ACLMessage.INFORM:
                        handleConfirmation(reply);
                        break;
                    case ACLMessage.REFUSE:
                        handleRefusal(reply);
                        break;
                }
            } else if (isTimeout()) {
                step = 2;
            } else {
                block(100);
            }
        }

        private void handleProposal(ACLMessage proposal) {
            propuestas.add(proposal);
            pendingProposals.put(proposal.getConversationId(), System.currentTimeMillis());
            
            if (propuestas.size() >= respuestasEsperadas) {
                step = 2;
            }
        }

        private void handleConfirmation(ACLMessage confirm) {
            String content = confirm.getContent();
            if (content.startsWith("ASSIGNED:")) {
                String[] parts = content.substring(9).split(",");
                String dia = parts[0];
                int bloque = Integer.parseInt(parts[1]);
                
                // Update occupancy map
                Asignatura currentAsignatura = asignaturas.get(asignaturaActual);
                occupancyMap.assignTimeSlot(dia, bloque, currentAsignatura.getNombre(), 
                    confirm.getSender().getLocalName(), 0);
                
                step = 3;
            }
        }

        private void handleRefusal(ACLMessage refuse) {
            pendingProposals.remove(refuse.getConversationId());
            if (pendingProposals.isEmpty() && propuestas.isEmpty()) {
                intentosActuales++;
                step = 0;
            }
        }

        private boolean isTimeout() {
            return System.currentTimeMillis() - tiempoInicio > TIMEOUT_RESPUESTA;
        }

        private void solicitarPropuestas() {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("sala");
            template.addServices(sd);

            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                if (result.length == 0) {
                    System.out.println("No hay salas disponibles para " + getAsignaturaActual().getNombre());
                    asignaturaActual++;
                    step = 0;
                    return;
                }

                ACLMessage msg = new ACLMessage(ACLMessage.CFP);
                for (DFAgentDescription agenteS : result) {
                    msg.addReceiver(agenteS.getName());
                }
                msg.setContent(asignaturas.get(asignaturaActual).toString());
                msg.setConversationId("negociacion-" + asignaturaActual + "-" + System.currentTimeMillis());
                myAgent.send(msg);
                
                respuestasEsperadas = result.length;
                mt = MessageTemplate.MatchConversationId(msg.getConversationId());
                
                System.out.println("Profesor " + nombre + " solicitó propuestas para " +
                        asignaturas.get(asignaturaActual).getNombre() +
                        " a " + result.length + " salas");
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }

        private void evaluarPropuestas() {
            ACLMessage mejorPropuesta = null;
            int mejorValoracion = -1;

            for (ACLMessage propuesta : propuestas) {
                String[] datos = propuesta.getContent().split(",");
                String dia = datos[0];
                int bloque = Integer.parseInt(datos[1]);
                
                if (!occupancyMap.isBlockAvailable(dia, bloque)) {
                    continue;
                }
                
                int capacidad = Integer.parseInt(datos[3]);
                int valoracion = evaluarPropuesta(datos[0], datos[2], capacidad);
                
                if (valoracion > mejorValoracion) {
                    mejorValoracion = valoracion;
                    mejorPropuesta = propuesta;
                }
            }

            if (mejorPropuesta != null) {
                aceptarPropuesta(mejorPropuesta, mejorValoracion);
                rechazarOtrasPropuestas(mejorPropuesta);
            } else {
                intentosActuales++;
                step = 0;
            }
        }

        private int evaluarPropuesta(String dia, String sala, int capacidad) {
            Asignatura asignatura = asignaturas.get(asignaturaActual);
            int vacantes = asignatura.getVacantes();
            
            // Return -1 if the time slot is already occupied
            if (!occupancyMap.isBlockAvailable(dia, Integer.parseInt(sala))) {
                return -1;
            }
            
            // Evaluate based on capacity match
            if (capacidad == vacantes) return 10;
            if (capacidad > vacantes) return 5;
            return 0;
        }

        private void aceptarPropuesta(ACLMessage propuesta, int valoracion) {
            ACLMessage accept = propuesta.createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            accept.setContent(propuesta.getContent() + "," + valoracion);
            myAgent.send(accept);

            String[] datos = propuesta.getContent().split(",");
            actualizarHorario(datos[0], datos[2], Integer.parseInt(datos[1]),
                    asignaturas.get(asignaturaActual), valoracion);
        }

        private void rechazarOtrasPropuestas(ACLMessage propuestaAceptada) {
            for (ACLMessage propuesta : propuestas) {
                if (!propuesta.equals(propuestaAceptada)) {
                    ACLMessage reject = propuesta.createReply();
                    reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    myAgent.send(reject);
                }
            }
        }

        private void prepararSiguienteAsignatura() {
            asignaturaActual++;
            intentosActuales = 0;
            if (asignaturaActual < asignaturas.size()) {
                step = 0;
                propuestas.clear();
                pendingProposals.clear();
                System.out.println("Profesor " + nombre + " pasando a siguiente asignatura (" +
                    asignaturaActual + "/" + asignaturas.size() + ")");
            } else {
                System.out.println("Profesor " + nombre + " completó todas sus asignaturas");
                finalizarNegociaciones();
                negociacionCompletada = true;
            }
        }

        private void handleNegotiationError() {
            intentosActuales++;
            if (intentosActuales >= MAX_INTENTOS_POR_ASIGNATURA) {
                System.out.println("Profesor " + nombre + ": Máximos intentos alcanzados para asignatura " +
                    asignaturas.get(asignaturaActual).getNombre() + ". Pasando a la siguiente.");
                asignaturaActual++;
                intentosActuales = 0;
            }
            step = 0;
            propuestas.clear();
            pendingProposals.clear();
            
            if (++reintentos >= MAX_REINTENTOS) {
                System.out.println("Profesor " + nombre + ": Máximos reintentos globales alcanzados. Finalizando negociaciones.");
                finalizarNegociaciones();
                negociacionCompletada = true;
            }
        }

        public boolean done() {
            return negociacionCompletada;
        }
    }

    private void actualizarHorario(String dia, String sala, int bloque, Asignatura asignatura, int valoracion) {
        JSONObject asignaturaJSON = new JSONObject();
        asignaturaJSON.put("Nombre", asignatura.getNombre());
        asignaturaJSON.put("Sala", sala);
        asignaturaJSON.put("Bloque", bloque);
        asignaturaJSON.put("Dia", dia);
        asignaturaJSON.put("Satisfaccion", valoracion);
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignaturaJSON);

        // Actualizar registro de horario ocupado
        String key = dia + "-" + sala;
        horarioOcupado.computeIfAbsent(key, k -> new HashSet<>()).add(String.valueOf(bloque));

        // Actualizar occupancy map
        occupancyMap.assignTimeSlot(dia, bloque, asignatura.getNombre(), sala, valoracion);

        System.out.println("Horario actualizado para " + nombre + ": " + asignatura.getNombre() +
                " en sala " + sala + ", día " + dia + ", bloque " + bloque +
                ", satisfacción " + valoracion);
    }

    private void finalizarNegociaciones() {
        try {
            isNegotiating.set(false);
            System.out.println("Profesor " + nombre + " (orden " + orden + ") finalizando negociaciones");
            
            // Save schedule before notifying next professor
            guardarHorario();
            
            // Notify next professor
            notificarSiguienteProfesor();
            
            // Cleanup and deregister
            cleanup();
            
        } catch (Exception e) {
            System.err.println("Error finalizando negociaciones para profesor " + nombre + 
                ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            doDelete();
        }
    }

    private void guardarHorario() {
        try {
            int asignaturasAsignadas = ((JSONArray)horarioJSON.get("Asignaturas")).size();
            System.out.println("Profesor " + nombre + " finalizó con " +
                asignaturasAsignadas + "/" + asignaturas.size() +
                " asignaturas asignadas");

            ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(
                nombre, horarioJSON, asignaturas.size());
        } catch (Exception e) {
            System.err.println("Error guardando horario para profesor " + nombre + 
                ": " + e.getMessage());
        }
    }

    private void notificarSiguienteProfesor() {
        final int MAX_RETRIES = 3;
        int attempts = 0;
        boolean success = false;

        while (attempts < MAX_RETRIES && !success) {
            try {
                Thread.sleep(2000); // Wait to ensure proper registration

                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("profesor");
                template.addServices(sd);

                DFAgentDescription[] result = DFService.search(this, template);
                boolean siguienteEncontrado = false;

                for (DFAgentDescription agente : result) {
                    String nombreAgente = agente.getName().getLocalName();
                    int ordenAgente = extraerOrden(nombreAgente);
                    if (ordenAgente == orden + 1) {
                        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                        msg.addReceiver(agente.getName());
                        msg.setContent("START");
                        send(msg);
                        System.out.println("Profesor " + nombre + " notificó al siguiente profesor: " + nombreAgente);
                        siguienteEncontrado = true;
                        success = true;
                        break;
                    }
                }

                if (!siguienteEncontrado) {
                    System.out.println("Profesor " + nombre + ": No se encontró siguiente profesor.");
                }
            } catch (Exception e) {
                System.err.println("Error al notificar siguiente profesor. Intento " + (attempts + 1));
                e.printStackTrace();
            }
            attempts++;
        }

        if (!success) {
            System.out.println("Profesor " + nombre + ": No se pudo notificar al siguiente profesor después de " + 
                MAX_RETRIES + " intentos.");
        }
    }

    private void cleanup() {
        try {
            DFService.deregister(this);
        } catch (Exception e) {
            System.err.println("Error during deregistration for profesor " + nombre + ": " + e.getMessage());
        }
    }

    private int extraerOrden(String nombreAgente) {
        try {
            if (nombreAgente.startsWith("Profesor")) {
                return Integer.parseInt(nombreAgente.substring(8));
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return Integer.MAX_VALUE;
    }

    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println("Profesor " + nombre + " terminando...");

        // Ensure schedule is saved before terminating
        if (horarioJSON != null) {
            guardarHorario();
        }
    }

    // Helper methods
    private Asignatura getAsignaturaActual() {
        if (asignaturaActual < asignaturas.size()) {
            return asignaturas.get(asignaturaActual);
        }
        return null;
    }

    private int getAsignaturasAsignadas() {
        if (horarioJSON != null && horarioJSON.get("Asignaturas") != null) {
            return ((JSONArray)horarioJSON.get("Asignaturas")).size();
        }
        return 0;
    }
}