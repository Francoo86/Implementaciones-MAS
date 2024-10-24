import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.core.AID;
import com.google.gson.Gson;
import java.util.*;
import java.io.*;

public class AgentTeacher extends Agent {
    private TeacherScheduleJSON teacherData;
    private OccupationMap schedule;
    private List<TeacherScheduleJSON.Subject> pendingSubjects;
    private List<TeacherScheduleJSON.UnassignedSubject> unassignedSubjects;
    private List<TeacherScheduleJSON.AssignedSubject> assignedSubjects;
    private int currentSubjectIndex = 0;

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            teacherData = (TeacherScheduleJSON) args[0];
            schedule = new OccupationMap();
            pendingSubjects = new ArrayList<>(teacherData.getAsignaturas());
            unassignedSubjects = new ArrayList<>();
            assignedSubjects = new ArrayList<>();

            // Add behavior for processing subjects sequentially
            addBehaviour(new ProcessSubjectsBehaviour());
        } else {
            System.err.println("Teacher agent needs initialization data!");
            doDelete();
        }
    }

    private class ProcessSubjectsBehaviour extends Behaviour {
        private boolean isDone = false;
        private int step = 0;
        private List<ACLMessage> proposals = new ArrayList<>();
        private TeacherScheduleJSON.Subject currentSubject;

        @Override
        public void action() {
            switch (step) {
                case 0: // Start negotiation for next subject
                    if (currentSubjectIndex < pendingSubjects.size()) {
                        currentSubject = pendingSubjects.get(currentSubjectIndex);
                        requestProposals(currentSubject);
                        step = 1;
                    } else {
                        saveScheduleToJson();
                        isDone = true;
                    }
                    break;

                case 1: // Collect proposals
                    ACLMessage reply = receive();
                    if (reply != null) {
                        if (reply.getPerformative() == ACLMessage.PROPOSE) {
                            proposals.add(reply);
                        }
                        // Wait for more proposals with timeout
                        if (proposals.size() >= getNumberOfRoomAgents()) {
                            step = 2;
                        }
                    } else {
                        block(1000); // Wait for proposals with timeout
                    }
                    break;

                case 2: // Evaluate proposals and select best
                    if (!proposals.isEmpty()) {
                        ACLMessage bestProposal = selectBestProposal(proposals);
                        if (bestProposal != null) {
                            acceptProposal(bestProposal, currentSubject);
                            step = 3;
                        } else {
                            handleUnassignedSubject(currentSubject, "No suitable proposals received");
                            moveToNextSubject();
                            step = 0;
                        }
                    } else {
                        handleUnassignedSubject(currentSubject, "No proposals received");
                        moveToNextSubject();
                        step = 0;
                    }
                    break;

                case 3: // Wait for confirmation and update schedule
                    ACLMessage confirmation = receive();
                    if (confirmation != null &&
                            confirmation.getPerformative() == ACLMessage.INFORM) {
                        updateSchedule(confirmation);
                        moveToNextSubject();
                        step = 0;
                    } else {
                        block(100);
                    }
                    break;
            }
        }

        private void requestProposals(TeacherScheduleJSON.Subject subject) {
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            cfp.setContent(new Gson().toJson(subject));
            cfp.setConversationId("schedule-negotiation");
            // Add all room agents as receivers
            for (AID roomAgent : getRoomAgents()) {
                cfp.addReceiver(roomAgent);
            }
            send(cfp);
        }

        private ACLMessage selectBestProposal(List<ACLMessage> proposals) {
            ACLMessage bestProposal = null;
            int highestSatisfaction = -1;
            long earliestTimestamp = Long.MAX_VALUE;

            for (ACLMessage proposal : proposals) {
                ProposalContent content = new Gson().fromJson(
                        proposal.getContent(),
                        ProposalContent.class
                );

                if (content.satisfaction > highestSatisfaction) {
                    highestSatisfaction = content.satisfaction;
                    bestProposal = proposal;
                    earliestTimestamp = proposal.getPostTimeStamp();
                } else if (content.satisfaction == highestSatisfaction &&
                        proposal.getPostTimeStamp() < earliestTimestamp) {
                    bestProposal = proposal;
                    earliestTimestamp = proposal.getPostTimeStamp();
                }
            }
            return bestProposal;
        }

        private void acceptProposal(ACLMessage proposal, TeacherScheduleJSON.Subject subject) {
            ACLMessage accept = proposal.createReply();
            accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
            accept.setContent(proposal.getContent());
            send(accept);
        }

        private void updateSchedule(ACLMessage confirmation) {
            ProposalContent content = new Gson().fromJson(
                    confirmation.getContent(),
                    ProposalContent.class
            );

            TeacherScheduleJSON.AssignedSubject assignedSubject =
                    new TeacherScheduleJSON.AssignedSubject();
            assignedSubject.setSala(content.roomCode);
            assignedSubject.setBloque(content.timeSlot.getBlock());
            assignedSubject.setDia(content.timeSlot.getDay());
            assignedSubject.setSatisfaccion(content.satisfaction);

            assignedSubjects.add(assignedSubject);
            schedule.addScheduleEntry(
                    content.timeSlot.getDay(),
                    content.timeSlot.getBlock(),
                    new ScheduleEntry(
                            currentSubject.getNombre(),
                            teacherData.getRut(),
                            content.roomCode,
                            content.satisfaction
                    )
            );
        }

        private void moveToNextSubject() {
            currentSubjectIndex++;
            proposals.clear();
        }

        @Override
        public boolean done() {
            return isDone;
        }
    }

    private void saveScheduleToJson() {
        TeacherScheduleJSON output = new TeacherScheduleJSON();
        output.setNombre(teacherData.getNombre());
        output.setRut(teacherData.getRut());
        output.setAsignaturas(assignedSubjects);
        output.setNoAsignados(unassignedSubjects);

        try (FileWriter writer = new FileWriter("horarios_asignados.json", true)) {
            new Gson().toJson(output, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}