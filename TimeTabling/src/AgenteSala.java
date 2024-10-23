import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class AgenteSala extends Agent implements SalaInterface {
    private String codigo;
    private int capacidad;
    private OccupancyMap occupancyMap;
    private int solicitudesProcesadas = 0;
    private int totalSolicitudes;
    private final Map<String, ReentrantLock> diaLocks = new HashMap<>();
    private final Map<String, PendingRequest> solicitudesPendientes = new ConcurrentHashMap<>();
    private static final long TIMEOUT_RESPUESTA = 5000; // 5 seconds timeout

    private static class PendingRequest {
        final long timestamp;
        final jade.core.AID sender;
        
        PendingRequest(jade.core.AID sender) {
            this.timestamp = System.currentTimeMillis();
            this.sender = sender;
        }
    }

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            String jsonString = (String) args[0];
            loadFromJsonString(jsonString);
        }

        // Initialize occupancy map
        occupancyMap = new OccupancyMap(codigo);

        // Initialize locks for each day
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            diaLocks.put(dia, new ReentrantLock());
        }

        System.out.println("Agente Sala " + codigo + " iniciado. Capacidad: " + capacidad);

        // Register with DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("sala");
        sd.setName(getName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        setEnabledO2ACommunication(true, 0);
        registerO2AInterface(SalaInterface.class, this);

        addBehaviour(new RecibirSolicitudBehaviour());
    }

    private void loadFromJsonString(String jsonString) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            codigo = (String) jsonObject.get("Codigo");
            capacidad = ((Number) jsonObject.get("Capacidad")).intValue();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setTotalSolicitudes(int total) {
        this.totalSolicitudes = total;
        System.out.println("Total de solicitudes establecido para sala " + codigo + ": " + total);
        addBehaviour(new VerificarFinalizacionBehaviour(this, 1000));
    }

    private class RecibirSolicitudBehaviour extends CyclicBehaviour {
        public void action() {
            // Clean up expired requests first
            limpiarSolicitudesExpiradas();

            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
            ACLMessage msg = myAgent.receive(mt);
            
            if (msg != null) {
                String conversationId = msg.getConversationId();
                solicitudesPendientes.put(conversationId, new PendingRequest(msg.getSender()));
                
                solicitudesProcesadas++;
                String solicitud = msg.getContent();
                Asignatura asignatura = Asignatura.fromString(solicitud);

                List<String> propuestas = generarPropuestas(asignatura);
                if (!propuestas.isEmpty()) {
                    enviarPropuestas(msg, propuestas, asignatura);
                } else {
                    // Send REFUSE if no proposals available
                    ACLMessage refuse = msg.createReply();
                    refuse.setPerformative(ACLMessage.REFUSE);
                    myAgent.send(refuse);
                    solicitudesPendientes.remove(conversationId);
                }
            } else {
                block();
            }
        }

        private void limpiarSolicitudesExpiradas() {
            long now = System.currentTimeMillis();
            solicitudesPendientes.entrySet().removeIf(entry -> {
                if (now - entry.getValue().timestamp > TIMEOUT_RESPUESTA) {
                    ACLMessage timeout = new ACLMessage(ACLMessage.REFUSE);
                    timeout.addReceiver(entry.getValue().sender);
                    timeout.setConversationId(entry.getKey());
                    myAgent.send(timeout);
                    return true;
                }
                return false;
            });
        }

        private void enviarPropuestas(ACLMessage msg, List<String> propuestas, Asignatura asignatura) {
            try {
                Thread.sleep((long) (Math.random() * 500 + 100));

                for (String propuesta : propuestas) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.PROPOSE);
                    reply.setContent(propuesta + "," + capacidad);
                    reply.setConversationId(msg.getConversationId());
                    myAgent.send(reply);
                }

                addBehaviour(new EsperarRespuestaBehaviour(myAgent, msg.getSender(), 
                    propuestas, asignatura.getNombre(), msg.getConversationId()));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private List<String> generarPropuestas(Asignatura asignatura) {
        List<String> propuestas = new ArrayList<>();
        
        // Generate one proposal for the first available block
        String[] dias = {"Lunes", "Martes", "Miercoles", "Jueves", "Viernes"};
        for (String dia : dias) {
            ReentrantLock lock = diaLocks.get(dia);
            if (lock.tryLock()) {
                try {
                    for (int bloque = 1; bloque <= 5; bloque++) {
                        if (occupancyMap.isBlockAvailable(dia, bloque)) {
                            propuestas.add(dia + "," + bloque + "," + codigo);
                            return propuestas; // Return only one proposal
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
        return propuestas;
    }

    private class EsperarRespuestaBehaviour extends Behaviour {
        private boolean received = false;
        private final MessageTemplate mt;
        private final String conversationId;
        private final String nombreAsignatura;

        public EsperarRespuestaBehaviour(Agent a, jade.core.AID sender, List<String> propuestas, 
                                       String nombreAsignatura, String conversationId) {
            super(a);
            this.mt = MessageTemplate.and(
                MessageTemplate.MatchSender(sender),
                MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)
            );
            this.conversationId = conversationId;
            this.nombreAsignatura = nombreAsignatura;
        }

        public void action() {
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null && msg.getConversationId().equals(conversationId)) {
                try {
                    String propuestaAceptada = msg.getContent();
                    String[] partes = propuestaAceptada.split(",");
                    String dia = partes[0];
                    int bloque = Integer.parseInt(partes[1]);
                    int valoracion = Integer.parseInt(partes[partes.length - 1]);

                    ReentrantLock lock = diaLocks.get(dia);
                    if (lock.tryLock()) {
                        try {
                            if (asignarBloque(dia, bloque, nombreAsignatura, msg.getSender().getLocalName(), valoracion)) {
                                ACLMessage confirm = msg.createReply();
                                confirm.setPerformative(ACLMessage.INFORM);
                                confirm.setContent("ASSIGNED:" + dia + "," + bloque);
                                myAgent.send(confirm);
                            }
                        } finally {
                            lock.unlock();
                        }
                    }
                    solicitudesPendientes.remove(conversationId);
                    received = true;
                } catch (Exception e) {
                    System.err.println("Error processing acceptance for room " + codigo + ": " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                block(100);
            }
        }

        public boolean done() {
            return received;
        }
    }

    private class VerificarFinalizacionBehaviour extends TickerBehaviour {
        public VerificarFinalizacionBehaviour(Agent a, long period) {
            super(a, period);
        }

        protected void onTick() {
            System.out.println("Verificando finalización para sala " + codigo +
                    ". Procesadas: " + solicitudesProcesadas +
                    " de " + totalSolicitudes +
                    ". Pendientes: " + solicitudesPendientes.size());

            // Check if all conditions for termination are met
            boolean shouldTerminate = solicitudesProcesadas >= totalSolicitudes && 
                                    solicitudesPendientes.isEmpty();

            if (shouldTerminate) {
                SalaHorarioJSON.getInstance().agregarHorarioSala(codigo, occupancyMap.toJSON());
                System.out.println("Sala " + codigo + " ha finalizado su asignación de horarios.");
                stop();
                myAgent.doDelete();
            }
        }
    }

    private synchronized boolean asignarBloque(String dia, int bloque, String asignatura, 
                                             String teacherName, int valoracion) {
        if (occupancyMap.assignTimeSlot(dia, bloque, asignatura, teacherName, valoracion)) {
            System.out.println("Sala " + codigo + ": Bloque asignado - " + dia + 
                ", bloque " + bloque + " para " + asignatura);
            return true;
        }
        return false;
    }
}