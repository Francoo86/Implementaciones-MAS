import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import com.google.gson.Gson;
import java.util.*;
import java.io.*;

public class AgentRoom extends Agent {
    private RoomJSON roomData;
    private OccupationMap schedule;
    private boolean isAssigned = false;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            roomData = (RoomJSON) args[0];
            schedule = new OccupationMap();

            // Add behavior for handling assignment requests
            addBehaviour(new HandleAssignmentRequestsBehaviour());
        } else {
            System.err.println("Room agent needs initialization data!");
            doDelete();
        }
    }

    private class HandleAssignmentRequestsBehaviour extends CyclicBehaviour {
        private MessageTemplate template = MessageTemplate.MatchConversationId(
                "schedule-negotiation"
        );

        @Override
        public void action() {
            ACLMessage msg = receive(template);
            if (msg != null) {
                switch (msg.getPerformative()) {
                    case ACLMessage.CFP:
                        if (!isAssigned) {
                            handleProposalRequest(msg);
                        }
                        break;

                    case ACLMessage.ACCEPT_PROPOSAL:
                        handleAcceptedProposal(msg);
                        break;
                }
            } else {
                block();
            }
        }

        private void handleProposalRequest(ACLMessage cfp) {
            TeacherScheduleJSON.Subject subject = new Gson().fromJson(
                    cfp.getContent(),
                    TeacherScheduleJSON.Subject.class
            );

            // Find available time slots
            List<TimeSlot> availableSlots = schedule.getAvailableTimeSlots();
            if (!availableSlots.isEmpty()) {
                // Calculate satisfaction based on capacity
                int satisfaction = SatisfactionCalculator.calculateSatisfaction(
                        roomData.getCapacidad(),
                        subject.getVacantes()
                );

                // Create proposal
                ProposalContent proposal = new ProposalContent(
                        roomData.getCodigo(),
                        availableSlots.get(0),
                        satisfaction
                );

                // Send proposal
                ACLMessage reply = cfp.createReply();
                reply.setPerformative(ACLMessage.PROPOSE);
                reply.setContent(new Gson().toJson(proposal));
                send(reply);
            }
        }

        private void handleAcceptedProposal(ACLMessage accept) {
            ProposalContent proposal = new Gson().fromJson(
                    accept.getContent(),
                    ProposalContent.class
            );

            // Update room schedule
            schedule.addScheduleEntry(
                    proposal.timeSlot.getDay(),
                    proposal.timeSlot.getBlock(),
                    new ScheduleEntry(
                            proposal.subjectName,
                            accept.getSender().getLocalName(),
                            roomData.getCodigo(),
                            proposal.satisfaction
                    )
            );

            // Mark room as assigned
            isAssigned = true;

            // Send confirmation
            ACLMessage confirm = accept.createReply();
            confirm.setPerformative(ACLMessage.INFORM);
            confirm.setContent(accept.getContent());
            send(confirm);

            // Save updated schedule
            saveScheduleToJson();
        }
    }

    private void saveScheduleToJson() {
        RoomScheduleJSON output = new RoomScheduleJSON();
        output.setNombre(roomData.getCodigo());
        output.setCodigo(roomData.getCodigo());

        List<RoomScheduleJSON.ScheduledSubject> scheduledSubjects =
                new ArrayList<>();

        for (Map.Entry<String, Map<Integer, ScheduleEntry>> dayEntry :
                schedule.getSchedule().entrySet()) {
            for (Map.Entry<Integer, ScheduleEntry> blockEntry :
                    dayEntry.getValue().entrySet()) {
                if (blockEntry.getValue() != null) {
                    RoomScheduleJSON.ScheduledSubject subject =
                            new RoomScheduleJSON.ScheduledSubject();
                    subject.setNombre(blockEntry.getValue().getSubjectName());
                    subject.setBloque(blockEntry.getKey());
                    subject.setDia(dayEntry.getKey());
                    scheduledSubjects.add(subject);
                }
            }
        }

        output.setAsignaturas(scheduledSubjects);

        try (FileWriter writer = new FileWriter("salidas_salas.json", true)) {
            new Gson().toJson(output, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}