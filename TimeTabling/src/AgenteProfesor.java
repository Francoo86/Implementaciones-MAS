import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import java.util.*;

public class AgenteProfesor extends Agent {
    private String nombre;
    private String rut;
    private List<Asignatura> asignaturas;
    private int asignaturaActual = 0;
    private Map<String, Set<Integer>> horarioOcupado; // dia -> bloques
    private int orden;
    private JSONObject horarioJSON;
    private static final long TIMEOUT_PROPUESTA = 5000; // 5 segundos para recibir propuestas
    private boolean isRegistered = false;
    private boolean isCleaningUp = false;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 1) {
            String jsonString = (String) args[0];
            orden = (Integer) args[1];
            cargarDatos(jsonString);
            registrarEnDF();
        }

        horarioOcupado = new HashMap<>();
        horarioJSON = new JSONObject();
        horarioJSON.put("Asignaturas", new JSONArray());

        if (orden == 0) {
            // Primer profesor, comenzar inmediatamente
            addBehaviour(new NegociarAsignaturasBehaviour());
        } else {
            // Esperar señal del profesor anterior
            addBehaviour(new EsperarTurnoBehaviour());
        }

        System.out.println("Profesor " + nombre + " (orden " + orden + ") iniciado");
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
            isRegistered = true;
            System.out.println("Profesor " + nombre + " registrado en DF");
        } catch (FIPAException fe) {
            System.err.println("Error registrando profesor " + nombre + " en DF: " + fe.getMessage());
            fe.printStackTrace();
        }
    }

    private void cargarDatos(String jsonString) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(jsonString);
            nombre = (String) jsonObject.get("Nombre");
            rut = (String) jsonObject.get("RUT");
            
            asignaturas = new ArrayList<>();
            JSONArray asignaturasJson = (JSONArray) jsonObject.get("Asignaturas");
            for (Object obj : asignaturasJson) {
                JSONObject asignaturaJson = (JSONObject) obj;
                asignaturas.add(new Asignatura(
                    (String) asignaturaJson.get("Nombre"),
                    0, 0,
                    ((Number) asignaturaJson.get("Horas")).intValue(),
                    ((Number) asignaturaJson.get("Vacantes")).intValue()
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class EsperarTurnoBehaviour extends CyclicBehaviour {
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null && msg.getContent().equals("START")) {
                myAgent.addBehaviour(new NegociarAsignaturasBehaviour());
                myAgent.removeBehaviour(this);
            } else {
                block();
            }
        }
    }

    private class NegociarAsignaturasBehaviour extends Behaviour {
        private int step = 0;
        private List<ACLMessage> propuestas;
        private boolean finished = false;
        private long tiempoInicio;

        public void action() {
            switch (step) {
                case 0: // Solicitar propuestas
                    if (asignaturaActual < asignaturas.size()) {
                        solicitarPropuestas();
                        propuestas = new ArrayList<>();
                        tiempoInicio = System.currentTimeMillis();
                        step = 1;
                    } else {
                        finalizarNegociaciones();
                        finished = true;
                    }
                    break;

                case 1: // Recolectar propuestas
                    MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                        MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                    );
                    ACLMessage reply = myAgent.receive(mt);
                    
                    if (reply != null) {
                        if (reply.getPerformative() == ACLMessage.PROPOSE) {
                            propuestas.add(reply);
                        }
                    }

                    // Verificar timeout
                    if (System.currentTimeMillis() - tiempoInicio > TIMEOUT_PROPUESTA) {
                        step = 2;
                    } else {
                        block(100);
                    }
                    break;

                case 2: // Evaluar propuestas
                    if (!propuestas.isEmpty()) {
                        evaluarPropuestas();
                    }
                    asignaturaActual++;
                    step = 0;
                    break;
            }
        }

        private void solicitarPropuestas() {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType("sala");
                template.addServices(sd);
                DFAgentDescription[] result = DFService.search(myAgent, template);

                if (result.length > 0) {
                    ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                    for (DFAgentDescription dfd : result) {
                        cfp.addReceiver(dfd.getName());
                    }

                    Asignatura asignatura = asignaturas.get(asignaturaActual);
                    cfp.setContent(asignatura.getNombre() + "," + asignatura.getVacantes());
                    cfp.setConversationId("neg-" + nombre + "-" + asignaturaActual);
                    myAgent.send(cfp);
                }
            } catch (FIPAException fe) {
                fe.printStackTrace();
            }
        }

        private void evaluarPropuestas() {
            // Ordenar propuestas por satisfacción
            propuestas.sort((p1, p2) -> {
                String[] data1 = p1.getContent().split(",");
                String[] data2 = p2.getContent().split(",");
                return Integer.parseInt(data2[4]) - Integer.parseInt(data1[4]);
            });

            // Tomar la mejor propuesta
            ACLMessage mejorPropuesta = propuestas.get(0);
            String[] datos = mejorPropuesta.getContent().split(",");
            String dia = datos[0];
            int bloque = Integer.parseInt(datos[1]);
            String sala = datos[2];
            int satisfaccion = Integer.parseInt(datos[4]);

            // Verificar si el horario está disponible
            if (!horarioOcupado.containsKey(dia) || 
                !horarioOcupado.get(dia).contains(bloque)) {
                
                // Aceptar propuesta - Formato: dia,bloque,nombreAsignatura,satisfaccion,sala
                ACLMessage accept = mejorPropuesta.createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent(String.format("%s,%d,%s,%d,%s",
                    dia,
                    bloque,
                    asignaturas.get(asignaturaActual).getNombre(),
                    satisfaccion,
                    sala));
                myAgent.send(accept);

                // Esperar confirmación
                MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchSender(mejorPropuesta.getSender()),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                );
                ACLMessage confirm = myAgent.blockingReceive(mt, 5000);

                if (confirm != null) {
                    // Actualizar horario
                    horarioOcupado.computeIfAbsent(dia, k -> new HashSet<>()).add(bloque);
                    actualizarHorarioJSON(dia, sala, bloque, satisfaccion);
                }
            }
        }

        public boolean done() {
            return finished;
        }
    }

    private void actualizarHorarioJSON(String dia, String sala, int bloque, int satisfaccion) {
        JSONObject asignatura = new JSONObject();
        asignatura.put("Nombre", asignaturas.get(asignaturaActual).getNombre());
        asignatura.put("Sala", sala);
        asignatura.put("Bloque", bloque);
        asignatura.put("Dia", dia);
        asignatura.put("Satisfaccion", satisfaccion);
        ((JSONArray) horarioJSON.get("Asignaturas")).add(asignatura);
        
        System.out.println("Profesor " + nombre + ": Asignada " + 
            asignaturas.get(asignaturaActual).getNombre() + 
            " en sala " + sala + ", día " + dia + 
            ", bloque " + bloque);
    }

    private void finalizarNegociaciones() {
        try {
            if (isCleaningUp) {
                return; // Evitar múltiples llamadas
            }
            isCleaningUp = true;

            // Guardar horario final
            ProfesorHorarioJSON.getInstance().agregarHorarioProfesor(
                nombre, horarioJSON, asignaturas.size());

            // Notificar al siguiente profesor antes de hacer cleanup
            notificarSiguienteProfesor();

            // Realizar cleanup y terminar
            cleanup();

        } catch (Exception e) {
            System.err.println("Error finalizando negociaciones para profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void notificarSiguienteProfesor() {
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("profesor");
            template.addServices(sd);
            
            DFAgentDescription[] result = DFService.search(this, template);
            int siguienteOrden = orden + 1;
            boolean siguienteEncontrado = false;

            for (DFAgentDescription dfd : result) {
                String nombreAgente = dfd.getName().getLocalName();
                if (nombreAgente.equals("Profesor" + siguienteOrden)) {
                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    msg.addReceiver(dfd.getName());
                    msg.setContent("START");
                    send(msg);
                    System.out.println("Profesor " + nombre + " notificó al siguiente profesor: " + nombreAgente);
                    siguienteEncontrado = true;
                    break;
                }
            }

            if (!siguienteEncontrado) {
                System.out.println("No hay más profesores después de " + nombre + ". Finalizando proceso.");
            }

        } catch (Exception e) {
            System.err.println("Error notificando siguiente profesor: " + e.getMessage());
        }
    }

    private synchronized void cleanup() {
        try {
            if (isRegistered) {
                // Verificar si aún estamos registrados antes de hacer deregister
                DFAgentDescription dfd = new DFAgentDescription();
                dfd.setName(getAID());
                DFAgentDescription[] result = DFService.search(this, dfd);
                
                if (result != null && result.length > 0) {
                    DFService.deregister(this);
                    System.out.println("Profesor " + nombre + " eliminado del DF");
                }
                isRegistered = false;
            }

            // Esperar un momento para asegurar que la notificación se envió
            Thread.sleep(1000);
            doDelete();

        } catch (Exception e) {
            System.err.println("Error durante cleanup de profesor " + nombre + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    protected void takeDown() {
        // Mostrar resumen final
        int asignadas = ((JSONArray) horarioJSON.get("Asignaturas")).size();
        System.out.println("Profesor " + nombre + " finalizado con " + 
            asignadas + "/" + asignaturas.size() + " asignaturas asignadas");
    }
}